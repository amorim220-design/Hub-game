#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Runtime em processo separado (modelo B: tradutor de CPU + Wine + jogo)
// ---------------------------------------------------------------------------
// O app NÃO carrega esse runtime como biblioteca: ele executa um lançador
// (ex.: box64) como processo filho e conversa com ele por:
//
//   * um socket UNIX (AF_UNIX, SOCK_STREAM): o app envia os eventos de
//     entrada e o processo filho avisa que desenhou um quadro novo;
//   * um framebuffer compartilhado (memfd): o processo escreve a imagem RGBA
//     e o app a apresenta na surface.
//
// O processo filho recebe tudo por variáveis de ambiente:
//
//   PCSHIM_SOCKET    caminho do socket UNIX
//   PCSHIM_FB_FD     número do fd do framebuffer (herdado no exec)
//   PCSHIM_FB_WIDTH  largura do framebuffer
//   PCSHIM_FB_HEIGHT altura do framebuffer
//
// Protocolo do socket (host -> guest), registro fixo de 28 bytes:
//
//   uint32 type     1=key down, 2=key up, 3=motion, 4=button, 5=focus
//   uint32 action   0=down/cancel, 1=up, 2=move
//   int32  a        keycode (tipos 1/2) ou índice do ponteiro (3)
//   int32  b        x em ponto fixo 16.16 (3) ou botão (4)
//   int32  c        y em ponto fixo 16.16 (3)
//   int64  ts       timestamp monotônico em ms
//
// Protocolo do socket (guest -> host), 1 byte:
//
//   'F'  um quadro novo foi escrito no framebuffer
//   'R'  o guest está pronto
//   'X'  o guest vai encerrar
// ---------------------------------------------------------------------------

#define PCSHIM_INPUT_KEY_DOWN 1u
#define PCSHIM_INPUT_KEY_UP 2u
#define PCSHIM_INPUT_MOTION 3u
#define PCSHIM_INPUT_BUTTON 4u
#define PCSHIM_INPUT_FOCUS 5u

#define PCSHIM_INPUT_RECORD_SIZE 28

struct PcshimInputEvent {
    uint32_t type = 0;
    uint32_t action = 0;
    int32_t a = 0;
    int32_t b = 0;
    int32_t c = 0;
    int64_t ts = 0;
};

struct ProcessRuntimeConfig {
    std::string launcher;                  // executável ARM64 (ex.: bin/box64)
    std::vector<std::string> args;        // argumentos do lançador
    std::map<std::string, std::string> env;
    std::string workingDir;
    int width = 1280;
    int height = 720;
    int acceptTimeoutMs = 15000;
};

class ProcessRuntime {
public:
    ProcessRuntime() = default;
    ~ProcessRuntime();

    ProcessRuntime(const ProcessRuntime&) = delete;
    ProcessRuntime& operator=(const ProcessRuntime&) = delete;

    // Inicia o processo filho. Retorna false e preenche errorLog em caso de falha.
    bool start(const ProcessRuntimeConfig& config, std::string& errorLog);

    // Encerra o processo (SIGTERM, depois SIGKILL se necessário).
    void stop();

    bool isRunning() const { return m_running.load(); }
    bool isGuestReady() const { return m_guestReady.load(); }

    // Envia um evento de entrada para o guest.
    bool sendInput(const PcshimInputEvent& event);

    // Consome uma notificação do guest. Retorna true se houve uma notificação.
    // 'F' indica um quadro novo (use framebuffer() para os pixels).
    bool pollGuestMessage(char& message);

    // Framebuffer compartilhado (RGBA, width*height*4 bytes).
    const uint8_t* framebuffer() const { return m_fbMapped; }
    uint32_t framebufferWidth() const { return m_width; }
    uint32_t framebufferHeight() const { return m_height; }

    int32_t childPid() const { return m_pid; }

private:
    bool createSocket(const std::string& path, std::string& errorLog);
    bool createFramebuffer(int width, int height, std::string& errorLog);
    void reapChild();

    int m_socketFd = -1;
    int m_fbFd = -1;
    uint8_t* m_fbMapped = nullptr;
    size_t m_fbSize = 0;
    uint32_t m_width = 0;
    uint32_t m_height = 0;
    int m_pid = -1;
    std::string m_socketPath;
    std::atomic<bool> m_running{false};
    std::atomic<bool> m_guestReady{false};
};
