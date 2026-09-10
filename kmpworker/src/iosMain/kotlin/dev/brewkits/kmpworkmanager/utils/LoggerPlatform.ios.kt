package dev.brewkits.kmpworkmanager.utils

import platform.Foundation.NSLog

/**
 * iOS implementation of Logger using NSLog for proper Xcode console output
 */
internal actual object LoggerPlatform {
    actual fun log(level: Logger.Level, message: String) {
        // NSLog treats its argument as a printf-style FORMAT string, so log text must never
        // reach it unescaped. Messages carry caller-supplied values — task ids, worker names,
        // URLs, error text — and a "%" in any of them is read as a conversion specifier
        // against an argument list that does not exist.
        //
        // Not theoretical: "Step 1/5 completed (20% complete, ...)" reached an iPhone 14 Pro
        // Max console as "20\u00CBomplete", because "%c" consumed a register and printed it as
        // a character. "%@" or "%s" dereference that register instead — a crash, or a pointer
        // disclosed into the log.
        //
        // Doubling every "%" is the fix rather than `NSLog("%@", message)`: NSLog is a C
        // variadic function, and passing an argument to it from Kotlin/Native segfaults the
        // process. "%%" is the one specifier that consumes no argument, so the escaped string
        // is inert whatever the message contains.
        NSLog(message.replace("%", "%%"))
    }
}
