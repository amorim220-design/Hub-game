/*
 * pcshim_guest — programa de referência para o runtime em processo (modelo B).
 *
 * Ele é um "guest" mínimo que implementa o contrato descrito em
 * app/src/main/cpp/process_runtime.h:
 *
 *   - conecta no socket UNIX informado em PCSHIM_SOCKET;
 *   - mapeia o framebuffer compartilhado (fd em PCSHIM_FB_FD) e escreve
 *     frames RGBA nele;
 *   - avisa o host com 'F' a cada quadro novo;
 *   - recebe eventos de entrada (28 bytes) e os reage (muda a cor do quadro).
 *
 * Serve para validar o caminho completo (app -> socket -> framebuffer ->
 * surface) num aparelho real, sem Wine e sem DXVK. O mesmo contrato é
 * usado pelo guest real (Box64 + Wine + DXVK).
 *
 * Compilação (a partir da raiz do repositório):
 *   ./tools/guest/build.sh
 *
 * Execução manual (o app faz isso por você):
 *   PCSHIM_SOCKET=/data/data/<pkg>/files/pcshim.sock \
 *   PCSHIM_FB_FD=5 PCSHIM_FB_WIDTH=1280 PCSHIM_FB_HEIGHT=720 ./pcshim_guest
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define INPUT_RECORD_SIZE 28

static uint8_t* g_fb = NULL;
static uint32_t g_width = 0;
static uint32_t g_height = 0;
static int g_socket = -1;

static int connect_socket(const char* path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        fprintf(stderr, "socket(): %s\n", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "connect(%s): %s\n", path, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int map_framebuffer(void) {
    const char* fd_env = getenv("PCSHIM_FB_FD");
    if (!fd_env) {
        fprintf(stderr, "PCSHIM_FB_FD nao definido\n");
        return -1;
    }
    int fd = atoi(fd_env);
    const char* w_env = getenv("PCSHIM_FB_WIDTH");
    const char* h_env = getenv("PCSHIM_FB_HEIGHT");
    g_width = w_env ? (uint32_t)atoi(w_env) : 1280;
    g_height = h_env ? (uint32_t)atoi(h_env) : 720;

    size_t size = (size_t)g_width * g_height * 4;
    g_fb = (uint8_t*)mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (g_fb == MAP_FAILED) {
        g_fb = NULL;
        fprintf(stderr, "mmap(): %s\n", strerror(errno));
        return -1;
    }
    memset(g_fb, 0, size);
    return 0;
}

/* Desenha um gradiente com um "cursor" que segue o input recebido. */
static void draw_frame(int cursor_x, int cursor_y) {
    for (uint32_t y = 0; y < g_height; y++) {
        uint8_t* row = g_fb + (size_t)y * g_width * 4;
        for (uint32_t x = 0; x < g_width; x++) {
            uint8_t* px = row + (size_t)x * 4;
            px[0] = (uint8_t)(x * 255 / (g_width ? g_width : 1));
            px[1] = (uint8_t)(y * 255 / (g_height ? g_height : 1));
            px[2] = (uint8_t)(32 + (x + y) % 64);
            px[3] = 0xff;
        }
    }
    if (cursor_x >= 0 && cursor_y >= 0 &&
        (uint32_t)cursor_x < g_width && (uint32_t)cursor_y < g_height) {
        uint8_t* px = g_fb + ((size_t)cursor_y * g_width + cursor_x) * 4;
        px[0] = 0xff; px[1] = 0xff; px[2] = 0xff;
    }
}

static int read_exact(int fd, void* buf, size_t len) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = read(fd, (uint8_t*)buf + got, len - got);
        if (n <= 0) return -1;
        got += (size_t)n;
    }
    return 0;
}

int main(void) {
    const char* socket_path = getenv("PCSHIM_SOCKET");
    if (!socket_path) {
        fprintf(stderr, "PCSHIM_SOCKET nao definido\n");
        return 2;
    }
    g_socket = connect_socket(socket_path);
    if (g_socket < 0) return 3;
    if (map_framebuffer() != 0) return 4;

    /* 'R' = pronto. */
    if (write(g_socket, "R", 1) != 1) return 5;

    int cursor_x = -1, cursor_y = -1;
    uint8_t record[INPUT_RECORD_SIZE];

    for (;;) {
        /* Evento de entrada (o host envia; pode chegar a qualquer momento). */
        if (read_exact(g_socket, record, INPUT_RECORD_SIZE) == 0) {
            uint32_t type = (uint32_t)record[0] | ((uint32_t)record[1] << 8) |
                            ((uint32_t)record[2] << 16) | ((uint32_t)record[3] << 24);
            int32_t b = (int32_t)((uint32_t)record[12] | ((uint32_t)record[13] << 8) |
                                  ((uint32_t)record[14] << 16) | ((uint32_t)record[15] << 24));
            int32_t c = (int32_t)((uint32_t)record[16] | ((uint32_t)record[17] << 8) |
                                  ((uint32_t)record[18] << 16) | ((uint32_t)record[19] << 24));
            if (type == 3 /* motion */) {
                cursor_x = b >> 16;
                cursor_y = c >> 16;
            }
        } else {
            break; /* o host fechou o socket */
        }

        draw_frame(cursor_x, cursor_y);
        if (write(g_socket, "F", 1) != 1) break;
    }

    if (g_socket >= 0) {
        ssize_t ignored = write(g_socket, "X", 1);
        (void)ignored;
        close(g_socket);
    }
    if (g_fb) munmap(g_fb, (size_t)g_width * g_height * 4);
    return 0;
}
