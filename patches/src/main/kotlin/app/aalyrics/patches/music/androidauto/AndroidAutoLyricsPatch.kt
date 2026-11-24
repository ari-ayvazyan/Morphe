package app.aalyrics.patches.music.androidauto

import app.aalyrics.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/aalyrics/extension/music/AndroidAutoLyricsPatch;"
private const val BROWSER_SERVICE_INTERFACE =
    "Lapp/aalyrics/extension/music/AndroidAutoLyricsPatch\$BrowserService;"
private const val BROWSE_RESULT_INTERFACE =
    "Lapp/aalyrics/extension/music/AndroidAutoLyricsPatch\$BrowseResult;"

private const val MUSIC_BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"
private const val MEDIA_DESCRIPTION_CLASS =
    "Landroid/support/v4/media/MediaDescriptionCompat;"
private const val MEDIA_ITEM_CLASS =
    "Landroid/support/v4/media/MediaBrowserCompat\$MediaItem;"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val BUNDLE = "Landroid/os/Bundle;"

/**
 * Adds a Lyrics tab to YouTube Music in Android Auto.
 *
 * Android Auto shows the folders that the app's browser service returns for the root folder as tabs.
 * This patch adds one folder to that list, answers requests for its contents with the lyrics of the
 * playing song, and reloads it when the song or the current line changes.
 *
 * Every hook is placed in the AndroidX media classes (found by their log strings) or on Android
 * framework methods, never in YouTube Music's own code. The hooks sit downstream of the official
 * Morphe "Android Auto" patch, so both can be applied to the same app, in either order.
 */
@Suppress("unused")
val androidAutoLyricsPatch = patch(
    name = "Android Auto lyrics",
    description = "Adds a Lyrics tab to Android Auto that shows the lyrics of the song that is playing. " +
            "Lyrics are downloaded from lrclib.net.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    extendWith("extensions/extension.mpe")

    val followPlayback by booleanOption(
        key = "followPlayback",
        default = true,
        title = "Follow playback",
        description = "When lyrics have timestamps, keep the current line at the top of the list. " +
                "If turned off, the whole song is listed and only refreshed when the song changes.",
    )

    execute {
        requireMediaItemConstructors()

        val loadChildren = findPerformLoadChildren()
        val resultType = hookLoadChildren(loadChildren)
        hookSendResult(resultType)
        hookPlayFromMediaId()
        hookMediaSession()

        mutableClassDefBy(EXTENSION_CLASS).methods.first { method ->
            method.name == "followPlayback"
        }.returnEarly(followPlayback != false)
    }
}

/** The extension creates Android Auto items with these two constructors, using reflection. */
private fun requireMediaItemConstructors() {
    fun requireConstructor(type: String, parameters: List<String>) {
        val classDef = classDefByOrNull(type)
            ?: throw PatchException("Could not find $type")
        classDef.methods.firstOrNull { method ->
            method.name == "<init>" && method.parameterTypes.map { it.toString() } == parameters
        } ?: throw PatchException("Could not find the constructor of $type that takes $parameters")
    }

    requireConstructor(
        MEDIA_DESCRIPTION_CLASS,
        listOf(
            STRING,
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Landroid/graphics/Bitmap;",
            "Landroid/net/Uri;",
            BUNDLE,
            "Landroid/net/Uri;"
        )
    )
    requireConstructor(MEDIA_ITEM_CLASS, listOf(MEDIA_DESCRIPTION_CLASS, "I"))
}

/** Finds performLoadChildren in the obfuscated AndroidX base class of MusicBrowserService. */
private fun findPerformLoadChildren(): Match {
    var type: String? = MUSIC_BROWSER_SERVICE_CLASS
    while (type != null) {
        val classDef = classDefByOrNull(type) ?: break
        PerformLoadChildrenFingerprint.matchOrNull(classDef)?.let { return it }
        type = classDef.superclass
    }
    throw PatchException("Could not find performLoadChildren in the superclasses of $MUSIC_BROWSER_SERVICE_CLASS")
}

/**
 * Lets the extension answer requests for the Lyrics folder, and remember which connection asked
 * so the folder can be reloaded later.
 *
 * @return The type of MediaBrowserServiceCompat.Result.
 */
private fun hookLoadChildren(match: Match): String {
    val originalMethod: Method = match.originalMethod
    val method = match.method
    val serviceClass = mutableClassDefBy(method.definingClass)

    val parameters = method.parameterTypes.map { it.toString() }
    val bundleCount = parameters.size - 2
    if (bundleCount !in 1..2 ||
        parameters[0] != STRING ||
        !parameters[1].startsWith("L") ||
        parameters.drop(2).any { it != BUNDLE }
    ) {
        throw PatchException("Unexpected parameters of performLoadChildren: $parameters")
    }
    if (AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("performLoadChildren is unexpectedly static")
    }

    // region Answer requests for the Lyrics folder.

    // performLoadChildren passes the request to the app with onLoadChildren(String, Result)
    // or onLoadChildren(String, Result, Bundle). Both names are obfuscated.
    class LoadCall(val index: Int, val instruction: Instruction, val reference: MethodReference)

    val resultTypes = mutableMapOf<String, Boolean>()
    fun isResultType(type: String) = resultTypes.getOrPut(type) {
        classDefByOrNull(type)?.let { sendResultFingerprint(type).matchOrNull(it) } != null
    }

    val loadCalls = method.implementation!!.instructions.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
            return@mapIndexedNotNull null
        }
        val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        val callParameters = reference.parameterTypes.map { it.toString() }
        val isLoadCall = reference.returnType == "V" &&
                callParameters.size in 2..3 &&
                callParameters[0] == STRING &&
                (callParameters.size == 2 || callParameters[2] == BUNDLE) &&
                isResultType(callParameters[1])
        if (isLoadCall) LoadCall(index, instruction, reference) else null
    }
    if (loadCalls.isEmpty()) {
        throw PatchException("Could not find the onLoadChildren call in performLoadChildren")
    }
    val resultType = loadCalls.first().reference.parameterTypes[1].toString()

    var hasPlainBridge = false
    var hasOptionsBridge = false

    loadCalls.forEach { call ->
        val withOptions = call.reference.parameterTypes.size == 3
        val bridgeName = if (withOptions) {
            "patch_lyricsLoadChildrenWithOptions"
        } else {
            "patch_lyricsLoadChildren"
        }

        // The bridge lets the extension pass on every request it does not handle.
        val alreadyAdded = if (withOptions) hasOptionsBridge else hasPlainBridge
        if (!alreadyAdded) {
            serviceClass.addPatchInterface(
                BROWSER_SERVICE_INTERFACE,
                bridgeName,
                """
                    check-cast p0, ${call.reference.definingClass}
                    check-cast p2, $resultType
                    invoke-virtual { p0, p1, p2${if (withOptions) ", p3" else ""} }, ${call.reference}
                    return-void
                """
            )
            if (withOptions) hasOptionsBridge = true else hasPlainBridge = true
        }

        val extensionMethod = "$EXTENSION_CLASS->loadChildren($OBJECT$STRING$OBJECT" +
                "${if (withOptions) BUNDLE else ""})V"
        // Replacing an instruction with another one does not move the later calls.
        method.replaceInstruction(
            call.index,
            when (val instruction = call.instruction) {
                is FiveRegisterInstruction -> {
                    val registers = listOf(
                        instruction.registerC,
                        instruction.registerD,
                        instruction.registerE,
                        instruction.registerF
                    ).take(instruction.registerCount).joinToString { "v$it" }
                    "invoke-static { $registers }, $extensionMethod"
                }

                is RegisterRangeInstruction -> {
                    val first = instruction.startRegister
                    val last = first + instruction.registerCount - 1
                    "invoke-static/range { v$first .. v$last }, $extensionMethod"
                }

                else -> throw PatchException("Unexpected onLoadChildren call: ${instruction.opcode}")
            }
        )
    }

    // The extension's interface declares both bridges, so add the one the app has no call for.
    if (!hasPlainBridge) {
        serviceClass.addPatchInterface(BROWSER_SERVICE_INTERFACE, "patch_lyricsLoadChildren", "return-void")
    }
    if (!hasOptionsBridge) {
        serviceClass.addPatchInterface(BROWSER_SERVICE_INTERFACE, "patch_lyricsLoadChildrenWithOptions", "return-void")
    }

    // endregion

    // region Reload the Lyrics folder.

    // Android Auto only shows new contents when the service sends them to its connection,
    // so the extension calls performLoadChildren again with the arguments of the first request.
    val invoke = if (AccessFlags.PRIVATE.isSet(method.accessFlags)) "invoke-direct" else "invoke-virtual"
    val reloadArguments = (0..bundleCount + 2).joinToString { "p$it" }
    serviceClass.addPatchInterface(
        BROWSER_SERVICE_INTERFACE,
        "patch_lyricsReload",
        """
            check-cast p2, ${parameters[1]}
            $invoke { $reloadArguments }, $originalMethod
            return-void
        """
    )

    method.addInstructions(
        0,
        "invoke-static/range { p0 .. p${bundleCount + 2} }, " +
                "$EXTENSION_CLASS->onSubscription($OBJECT$STRING$OBJECT${BUNDLE.repeat(bundleCount)})V"
    )

    // endregion

    return resultType
}

/** Adds the Lyrics tab to the tabs the app returns for Android Auto's root folder. */
private fun hookSendResult(resultType: String) {
    val resultClassDef = classDefByOrNull(resultType)
        ?: throw PatchException("Could not find $resultType")
    val match = sendResultFingerprint(resultType).matchOrNull(resultClassDef)
        ?: throw PatchException("Could not find sendResult in $resultType")
    val originalMethod: Method = match.originalMethod
    val method = match.method
    val resultClass = mutableClassDefBy(resultType)

    // The result has no getter for the folder it belongs to, but keeps the folder's ID for
    // its error messages. It is the only Object field of the class.
    val parentIdField = method.implementation!!.instructions.firstNotNullOfOrNull { instruction ->
        instruction.getReference<FieldReference>()?.takeIf { field ->
            field.definingClass == resultType && field.type == OBJECT
        }
    } ?: throw PatchException("Could not find the folder ID field of $resultType")

    resultClass.addPatchInterface(BROWSE_RESULT_INTERFACE, "patch_lyricsGetParentId", parentIdField)
    resultClass.addPatchInterface(BROWSE_RESULT_INTERFACE, "patch_lyricsSendResult", originalMethod)

    method.addInstructions(
        0,
        """
            invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onSendResult($OBJECT$OBJECT)$OBJECT
            move-result-object p1
        """
    )
}

/**
 * Tapping a line of the lyrics asks the app to play the line's media ID, which the app does not know.
 * The extension handles these IDs (it seeks to the line) and hides them from the app.
 */
private fun hookPlayFromMediaId() {
    PlayFromMediaIdFingerprint.matchAll().forEach { match ->
        // The extension returns null for its own IDs, so no extra register is needed.
        match.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onPlayFromMediaId($STRING)$STRING
                move-result-object p1
                if-nez p1, :resume
                return-void
                :resume
                nop
            """
        )
    }
}

/** Gives the extension the app's media session, which it reads the song and position from. */
private fun hookMediaSession() {
    SetMetadataFingerprint.matchAll().forEach { match ->
        val index = match.instructionMatches.first().index
        val sessionRegister = when (val instruction = match.method.getInstruction<Instruction>(index)) {
            is FiveRegisterInstruction -> instruction.registerC
            is RegisterRangeInstruction -> instruction.startRegister
            else -> throw PatchException("Unexpected setMetadata call: ${instruction.opcode}")
        }

        match.method.addInstruction(
            index,
            "invoke-static/range { v$sessionRegister .. v$sessionRegister }, " +
                    "$EXTENSION_CLASS->onMediaSession(Landroid/media/session/MediaSession;)V"
        )
    }
}
