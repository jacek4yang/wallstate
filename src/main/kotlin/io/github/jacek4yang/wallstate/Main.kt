package io.github.jacek4yang.wallstate

/**
 * Device-side entry point, executed via:
 *
 *   CLASSPATH=/data/local/tmp/wallstate.jar \
 *     app_process /system/bin io.github.jacek4yang.wallstate.Main <command> [args]
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        kotlin.system.exitProcess(Cli.run(args))
    }
}
