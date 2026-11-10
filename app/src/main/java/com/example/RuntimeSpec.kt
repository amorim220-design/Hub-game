package com.example

import org.json.JSONObject
import java.io.File

/**
 * Descrição de um "pacote de runtime": o que o app carrega para executar o jogo.
 *
 * Dois modelos são suportados:
 *
 *  - [Native]: o pacote traz uma biblioteca ARM64 (.so) que já é o motor do
 *    jogo recompilado, com o DXVK embutido. A biblioteca expõe o ponto de
 *    entrada `game_main_tick()` e, opcionalmente, `pcshim_input_event()`.
 *    Exemplo: o build de `codepdbh/nfsu2-recompiled`.
 *
 *  - [Wine]: o pacote traz um prefixo do Wine, o tradutor de CPU (Box64/FEX)
 *    e o DXVK (d3d9.dll de 32 bits). O app NÃO carrega isso como biblioteca:
 *    ele executa o lançador (`launcher`) como processo e conversa com ele
 *    através de um socket UNIX (eventos de entrada) e de um framebuffer
 *    compartilhado (imagem apresentada na surface Vulkan).
 */
sealed class RuntimeSpec {

    abstract val type: String

    /** Tipo nativo: uma .so ARM64 carregada no próprio processo do jogo. */
    data class Native(
        val mainLibrary: String,
        val entryPoint: String = "game_main_tick"
    ) : RuntimeSpec() {
        override val type: String = "native"
    }

    /**
     * Tipo Wine: um processo separado (tradutor + Wine + jogo).
     *
     * @param prefixDir pasta do prefixo do Wine, relativa à raiz do pacote.
     * @param exe caminho do executável dentro do prefixo (ex.: drive_c/jogo/Game.exe).
     * @param launcher executável ARM64 que inicia o tradutor (ex.: bin/box64).
     * @param launcherArgs argumentos passados ao lançador.
     * @param env variáveis de ambiente adicionais (WINEPREFIX, WINEDLLPATH, DXVK_*).
     * @param displayWidth largura do framebuffer compartilhado.
     * @param displayHeight altura do framebuffer compartilhado.
     * @param inputBridge .so opcional injetada no processo convidado para
     *        consumir os eventos de entrada recebidos pelo socket.
     */
    data class Wine(
        val prefixDir: String,
        val exe: String,
        val launcher: String,
        val launcherArgs: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val displayWidth: Int = 1280,
        val displayHeight: Int = 720,
        val inputBridge: String? = null
    ) : RuntimeSpec() {
        override val type: String = "wine"
    }

    companion object {
        const val TYPE_NATIVE = "native"
        const val TYPE_WINE = "wine"

        /**
         * Lê e valida a especificação a partir do meta.json do pacote.
         * Lança [IllegalArgumentException] com uma mensagem legível quando
         * o pacote é inválido — o instalador usa isso para recusar o pacote.
         */
        fun fromJson(json: JSONObject): RuntimeSpec {
            val type = json.optString("type", TYPE_NATIVE).lowercase()

            return when (type) {
                TYPE_NATIVE -> {
                    val mainLibrary = json.optString("mainLibrary").takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException(
                            "Pacote do tipo 'native' precisa do campo \"mainLibrary\" (a .so ARM64 do motor)."
                        )
                    Native(
                        mainLibrary = mainLibrary,
                        entryPoint = json.optString("entryPoint", "game_main_tick")
                    )
                }

                TYPE_WINE -> {
                    val prefix = json.optString("prefix").takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException(
                            "Pacote do tipo 'wine' precisa do campo \"prefix\" (a pasta do prefixo do Wine)."
                        )
                    val exe = json.optString("exe").takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException(
                            "Pacote do tipo 'wine' precisa do campo \"exe\" (o executável dentro do prefixo)."
                        )
                    val launcher = json.optString("launcher").takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException(
                            "Pacote do tipo 'wine' precisa do campo \"launcher\" " +
                                "(o executável ARM64 que inicia o tradutor, ex.: bin/box64)."
                        )

                    val args = mutableListOf<String>()
                    val argsJson = json.optJSONArray("launcherArgs")
                    if (argsJson != null) {
                        for (i in 0 until argsJson.length()) {
                            args.add(argsJson.optString(i))
                        }
                    }

                    val env = mutableMapOf<String, String>()
                    val envJson = json.optJSONObject("env")
                    if (envJson != null) {
                        envJson.keys().forEach { key -> env[key] = envJson.optString(key) }
                    }

                    val width = json.optInt("displayWidth", 1280)
                    val height = json.optInt("displayHeight", 720)
                    if (width !in 64..7680 || height !in 64..4320) {
                        throw IllegalArgumentException(
                            "Dimensões de display inválidas: ${width}x${height} (aceito 64..7680 x 64..4320)."
                        )
                    }

                    Wine(
                        prefixDir = prefix,
                        exe = exe,
                        launcher = launcher,
                        launcherArgs = args,
                        env = env,
                        displayWidth = width,
                        displayHeight = height,
                        inputBridge = json.optString("inputBridge").takeIf { it.isNotBlank() }
                    )
                }

                else -> throw IllegalArgumentException(
                    "Tipo de runtime desconhecido: \"$type\" (aceitos: \"$TYPE_NATIVE\", \"$TYPE_WINE\")."
                )
            }
        }

        /** Serializa a especificação (usado ao gravar o meta.json instalado). */
        fun toJson(spec: RuntimeSpec): JSONObject = JSONObject().apply {
            put("type", spec.type)
            when (spec) {
                is Native -> {
                    put("mainLibrary", spec.mainLibrary)
                    put("entryPoint", spec.entryPoint)
                }
                is Wine -> {
                    put("prefix", spec.prefixDir)
                    put("exe", spec.exe)
                    put("launcher", spec.launcher)
                    put("launcherArgs", org.json.JSONArray(spec.launcherArgs))
                    put("env", JSONObject(spec.env))
                    put("displayWidth", spec.displayWidth)
                    put("displayHeight", spec.displayHeight)
                    if (spec.inputBridge != null) put("inputBridge", spec.inputBridge)
                }
            }
        }

        /**
         * Valida os arquivos do pacote contra a especificação.
         * Retorna a lista de problemas encontrados (vazia = pacote válido).
         */
        fun validateFiles(spec: RuntimeSpec, root: File): List<String> {
            val problems = mutableListOf<String>()

            fun resolveSafe(relative: String): File? {
                val target = File(root, relative)
                val rootPath = root.canonicalPath
                val targetPath = target.canonicalPath
                return if (targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
                    target
                } else {
                    problems.add("Caminho fora do pacote: $relative")
                    null
                }
            }

            when (spec) {
                is Native -> {
                    val lib = resolveSafe(spec.mainLibrary)
                    if (lib == null || !lib.isFile) {
                        problems.add("A biblioteca principal \"${spec.mainLibrary}\" não existe no pacote.")
                    }
                }

                is Wine -> {
                    val prefix = resolveSafe(spec.prefixDir)
                    if (prefix == null || !prefix.isDirectory) {
                        problems.add("O prefixo \"${spec.prefixDir}\" não existe no pacote.")
                    }

                    val launcher = resolveSafe(spec.launcher)
                    if (launcher == null || !launcher.isFile) {
                        problems.add("O lançador \"${spec.launcher}\" não existe no pacote.")
                    }

                    // O executável do jogo vive dentro do prefixo; o prefixo do
                    // Wine guarda o drive C em "drive_c".
                    val exeRelative = if (spec.exe.startsWith("drive_c/")) {
                        "${spec.prefixDir}/${spec.exe}"
                    } else {
                        "${spec.prefixDir}/drive_c/${spec.exe}"
                    }
                    val exe = resolveSafe(exeRelative)
                    if (exe == null || !exe.isFile) {
                        problems.add(
                            "O executável do jogo \"${spec.exe}\" não foi encontrado no prefixo " +
                                "(procurado em \"$exeRelative\")."
                        )
                    }

                    if (spec.inputBridge != null) {
                        val bridge = resolveSafe(spec.inputBridge)
                        if (bridge == null || !bridge.isFile) {
                            problems.add("A ponte de input \"${spec.inputBridge}\" não existe no pacote.")
                        }
                    }
                }
            }

            return problems
        }
    }
}
