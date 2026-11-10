#include "process_runtime.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#include <thread>

// memfd_create não está disponível na API 29 do Android: usamos a syscall
// diretamente, com o número por arquitetura.
#if defined(__aarch64__)
#define PCSHIM_NR_MEMFD_CREATE 279
#elif defined(__x86_64__)
#define PCSHIM_NR_MEMFD_CREATE 319
#else
#error "arquitetura não suportada pelo runtime em processo"
#endif

#define LOG_TAG "VulkanCompatProcess"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Serializa o registro de entrada no formato de wire (28 bytes, big endian
// não é necessário: o host e o guest são o mesmo binário).
size_t encodeInput(const PcshimInputEvent& e, uint8_t* out) {
    auto put32 = [&](size_t off, uint32_t v) {
        out[off + 0] = (uint8_t)(v & 0xff);
        out[off + 1] = (uint8_t)((v >> 8) & 0xff);
        out[off + 2] = (uint8_t)((v >> 16) & 0xff);
        out[off + 3] = (uint8_t)((v >> 24) & 0xff);
    };
    auto put64 = [&](size_t off, uint64_t v) {
        put32(off, (uint32_t)(v & 0xffffffffu));
        put32(off + 4, (uint32_t)((v >> 32) & 0xffffffffu));
    };

    put32(0, e.type);
    put32(4, e.action);
    put32(8, (uint32_t)e.a);
    put32(12, (uint32_t)e.b);
    put32(16, (uint32_t)e.c);
    put64(20, (uint64_t)e.ts);
    return PCSHIM_INPUT_RECORD_SIZE;
}

}  // namespace

ProcessRuntime::~ProcessRuntime() {
    stop();
}

void ProcessRuntime::reapChild() {
    if (m_pid > 0) {
        int status = 0;
        // Não bloqueia para sempre: se o guest já estiver morto, waitpid
        // retorna na hora; se não, o SIGTERM já foi enviado.
        waitpid(m_pid, &status, WNOHANG);
    }
}

bool ProcessRuntime::createSocket(const std::string& path, std::string& errorLog) {
    m_socketFd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (m_socketFd < 0) {
        errorLog = std::string("socket(): ") + strerror(errno);
        return false;
    }

    unlink(path.c_str());

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (path.size() >= sizeof(addr.sun_path)) {
        errorLog = "O caminho do socket é longo demais para sun_path (" +
                   std::to_string(path.size()) + " >= " + std::to_string(sizeof(addr.sun_path)) + ")";
        close(m_socketFd);
        m_socketFd = -1;
        return false;
    }
    memcpy(addr.sun_path, path.c_str(), path.size());

    if (bind(m_socketFd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        errorLog = std::string("bind(): ") + strerror(errno);
        close(m_socketFd);
        m_socketFd = -1;
        return false;
    }
    if (listen(m_socketFd, 1) < 0) {
        errorLog = std::string("listen(): ") + strerror(errno);
        close(m_socketFd);
        m_socketFd = -1;
        return false;
    }
    m_socketPath = path;
    return true;
}

bool ProcessRuntime::createFramebuffer(int width, int height, std::string& errorLog) {
    m_width = (uint32_t)width;
    m_height = (uint32_t)height;
    m_fbSize = (size_t)width * (size_t)height * 4u;

    // memfd_create: memória compartilhada anônima, herdada pelo filho no exec.
    m_fbFd = (int)syscall(PCSHIM_NR_MEMFD_CREATE, "pcshim_fb", 0 /* sem CLOEXEC: o filho precisa */);
    if (m_fbFd < 0) {
        errorLog = std::string("memfd_create(): ") + strerror(errno);
        return false;
    }
    if (ftruncate(m_fbFd, (off_t)m_fbSize) < 0) {
        errorLog = std::string("ftruncate(): ") + strerror(errno);
        close(m_fbFd);
        m_fbFd = -1;
        return false;
    }
    m_fbMapped = (uint8_t*)mmap(nullptr, m_fbSize, PROT_READ | PROT_WRITE, MAP_SHARED, m_fbFd, 0);
    if (m_fbMapped == MAP_FAILED) {
        m_fbMapped = nullptr;
        errorLog = std::string("mmap(): ") + strerror(errno);
        close(m_fbFd);
        m_fbFd = -1;
        return false;
    }
    memset(m_fbMapped, 0, m_fbSize);
    return true;
}

bool ProcessRuntime::start(const ProcessRuntimeConfig& config, std::string& errorLog) {
    if (m_running.load()) {
        errorLog = "O runtime já está em execução.";
        return false;
    }

    // O socket precisa de um caminho curto (< 108 bytes): usamos o diretório
    // de arquivos do app.
    std::string socketPath = "/data/data/com.aistudio.vulkancompat.qznvwt/files/pcshim.sock";

    if (!createSocket(socketPath, errorLog)) return false;
    if (!createFramebuffer(config.width, config.height, errorLog)) {
        close(m_socketFd);
        m_socketFd = -1;
        unlink(m_socketPath.c_str());
        return false;
    }

    pid_t pid = fork();
    if (pid < 0) {
        errorLog = std::string("fork(): ") + strerror(errno);
        close(m_socketFd);
        m_socketFd = -1;
        close(m_fbFd);
        m_fbFd = -1;
        unlink(m_socketPath.c_str());
        return false;
    }

    if (pid == 0) {
        // ---- processo filho ----
        // O socket e o framebuffer são passados pelo ambiente.
        setenv("PCSHIM_SOCKET", socketPath.c_str(), 1);
        setenv("PCSHIM_FB_FD", std::to_string(m_fbFd).c_str(), 1);
        setenv("PCSHIM_FB_WIDTH", std::to_string(config.width).c_str(), 1);
        setenv("PCSHIM_FB_HEIGHT", std::to_string(config.height).c_str(), 1);

        for (const auto& kv : config.env) {
            setenv(kv.first.c_str(), kv.second.c_str(), 1);
        }
        if (!config.workingDir.empty()) {
            if (chdir(config.workingDir.c_str()) != 0) {
                LOGW("chdir(%s) falhou: %s", config.workingDir.c_str(), strerror(errno));
            }
        }

        // stdin/stdout/stderr não podem ser o terminal: o app não tem um.
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) close(devnull);
        }

        std::vector<char*> argv;
        argv.push_back(const_cast<char*>(config.launcher.c_str()));
        for (const auto& a : config.args) {
            argv.push_back(const_cast<char*>(a.c_str()));
        }
        argv.push_back(nullptr);

        execv(config.launcher.c_str(), argv.data());
        // Se chegou aqui, o exec falhou.
        _exit(127);
    }

    m_pid = pid;
    m_running.store(true);

    // Espera o guest conectar (com timeout).
    struct timeval tv;
    tv.tv_sec = config.acceptTimeoutMs / 1000;
    tv.tv_usec = (config.acceptTimeoutMs % 1000) * 1000;
    setsockopt(m_socketFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    int clientFd = accept(m_socketFd, nullptr, nullptr);
    if (clientFd < 0) {
        LOGW("O guest não conectou em %d ms: %s", config.acceptTimeoutMs, strerror(errno));
    } else {
        // A partir de agora o socket do processo filho é o que usamos.
        close(m_socketFd);
        m_socketFd = clientFd;
        LOGI("Guest conectado (pid=%d)", m_pid);
    }

    return true;
}

void ProcessRuntime::stop() {
    if (m_pid > 0) {
        kill(m_pid, SIGTERM);
        // Espera um pouco e insiste.
        for (int i = 0; i < 30; i++) {
            int status = 0;
            pid_t r = waitpid(m_pid, &status, WNOHANG);
            if (r == m_pid || (r < 0 && errno == ECHILD)) {
                m_pid = -1;
                break;
            }
            usleep(100 * 1000);
        }
        if (m_pid > 0) {
            kill(m_pid, SIGKILL);
            int status = 0;
            waitpid(m_pid, &status, 0);
            m_pid = -1;
        }
    }
    m_running.store(false);
    m_guestReady.store(false);

    if (m_socketFd >= 0) {
        close(m_socketFd);
        m_socketFd = -1;
    }
    if (!m_socketPath.empty()) {
        unlink(m_socketPath.c_str());
        m_socketPath.clear();
    }
    if (m_fbMapped) {
        munmap(m_fbMapped, m_fbSize);
        m_fbMapped = nullptr;
    }
    if (m_fbFd >= 0) {
        close(m_fbFd);
        m_fbFd = -1;
    }
}

bool ProcessRuntime::sendInput(const PcshimInputEvent& event) {
    if (m_socketFd < 0) return false;
    uint8_t buffer[PCSHIM_INPUT_RECORD_SIZE];
    size_t len = encodeInput(event, buffer);
    ssize_t written = write(m_socketFd, buffer, len);
    return written == (ssize_t)len;
}

bool ProcessRuntime::pollGuestMessage(char& message) {
    if (m_socketFd < 0) return false;
    uint8_t byte = 0;
    ssize_t n = read(m_socketFd, &byte, 1);
    if (n != 1) return false;
    message = (char)byte;
    if (message == 'R') {
        m_guestReady.store(true);
    } else if (message == 'X') {
        m_running.store(false);
    }
    return true;
}
