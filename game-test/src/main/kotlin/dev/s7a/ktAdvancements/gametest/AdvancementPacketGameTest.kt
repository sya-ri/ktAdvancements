package dev.s7a.ktAdvancements.gametest

import dev.s7a.ktAdvancements.KtAdvancement
import dev.s7a.ktAdvancements.KtAdvancementStore
import dev.s7a.ktAdvancements.KtAdvancements
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.Optional
import java.util.Properties
import kotlin.math.abs

internal class AdvancementPacketGameTest(
    private val craftServerClass: Class<*>,
) {
    private val capturedPackets = mutableListOf<Any>()
    private val store = RecordingStore()
    private val advancements = KtAdvancements(TestAdvancement.entries, store)
    private val runtime =
        requireNotNull(
            advancements.javaClass.declaredFields
                .single { it.type.name == "dev.s7a.ktAdvancements.runtime.KtAdvancementRuntime" }
                .read(advancements),
        )

    fun run(): Properties {
        validateDefinitions()
        val player = createMockPlayer()

        advancements.showAll(player)
        validatePacket(
            takePacket(),
            expectedReset = true,
            expectedAdded = setOf(TestAdvancement.Root, TestAdvancement.Progress),
            expectedRemoved = setOf(TestAdvancement.Hidden),
            expectedProgress = mapOf(TestAdvancement.Root to 1, TestAdvancement.Progress to 0),
        )

        check(advancements.grant(player, TestAdvancement.Progress, step = 3))
        validatePacket(
            takePacket(),
            expectedReset = false,
            expectedAdded = setOf(TestAdvancement.Progress),
            expectedProgress = mapOf(TestAdvancement.Progress to 3),
        )

        check(advancements.grant(player, TestAdvancement.Progress, step = 7))
        validatePacket(
            takePacket(),
            expectedReset = false,
            expectedAdded = setOf(TestAdvancement.Progress),
            expectedProgress = mapOf(TestAdvancement.Progress to 10),
        )

        check(advancements.revoke(player, TestAdvancement.Progress, step = 1))
        validatePacket(
            takePacket(),
            expectedReset = false,
            expectedAdded = setOf(TestAdvancement.Progress),
            expectedProgress = mapOf(TestAdvancement.Progress to 9),
        )

        check(advancements.grant(player, TestAdvancement.Hidden, step = 1))
        validatePacket(
            takePacket(),
            expectedReset = false,
            expectedAdded = setOf(TestAdvancement.Hidden),
            expectedProgress = mapOf(TestAdvancement.Hidden to 1),
        )

        check(advancements.revoke(player, TestAdvancement.Hidden, step = 1))
        validatePacket(
            takePacket(),
            expectedReset = false,
            expectedRemoved = setOf(TestAdvancement.Hidden),
            expectedProgress = emptyMap(),
        )

        check(capturedPackets.isEmpty()) { "Unexpected extra packets: ${capturedPackets.size}" }
        return Properties().apply {
            setProperty("status", "passed")
            setProperty("runtime", runtimeName())
            setProperty("progress", "0/10,3/10,10/10,9/10")
            setProperty("visibility", "hidden,visible,hidden")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createMockPlayer(): Player {
        val craftPlayerClass =
            Class.forName("${craftServerClass.packageName}.entity.CraftPlayer") as Class<Any>
        val serverPlayerClass =
            craftPlayerClass.methods
                .filter { it.name == "getHandle" && it.parameterCount == 0 }
                .map(Method::getReturnType)
                .first { it.name.startsWith("net.minecraft.server.level.") }
                .let { it as Class<Any> }
        val connectionField =
            allFields(serverPlayerClass).single {
                !Modifier.isStatic(it.modifiers) &&
                    (it.type.simpleName.contains("Connection") || it.type.simpleName.contains("PacketListener"))
            }
        val connectionClass = connectionField.type as Class<Any>
        val connection =
            Mockito.mock(
                connectionClass,
                Answer { invocation ->
                    invocation.arguments
                        .singleOrNull()
                        ?.takeIf { it.javaClass.simpleName.contains("Advancement", ignoreCase = true) }
                        ?.let(capturedPackets::add)
                    Answers.RETURNS_DEFAULTS.answer(invocation)
                },
            )
        val serverPlayer = Mockito.mock(serverPlayerClass, Answers.RETURNS_DEFAULTS)
        connectionField.trySetAccessible()
        connectionField.set(serverPlayer, connection)

        return Mockito.mock(
            craftPlayerClass,
            Answer { invocation ->
                if (invocation.method.name == "getHandle" && invocation.method.parameterCount == 0) {
                    serverPlayer
                } else {
                    Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            },
        ) as Player
    }

    private fun takePacket(): Any {
        check(capturedPackets.size == 1) { "Expected one advancement packet, got ${capturedPackets.size}" }
        return capturedPackets.removeAt(0)
    }

    private fun validatePacket(
        packet: Any,
        expectedReset: Boolean,
        expectedAdded: Set<TestAdvancement> = emptySet(),
        expectedRemoved: Set<TestAdvancement> = emptySet(),
        expectedProgress: Map<TestAdvancement, Int>,
    ) {
        val fields = allFields(packet.javaClass).filterNot { Modifier.isStatic(it.modifiers) }
        val booleanValues =
            fields.filter { it.type == Boolean::class.javaPrimitiveType }.map { it.read(packet) as Boolean }
        check(booleanValues.size in 1..2) { "Unexpected advancement packet flags in ${packet.javaClass.name}" }
        val reset =
            packet.javaClass.methods
                .firstOrNull {
                    it.name == "shouldReset" &&
                        it.parameterCount == 0 &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }?.invoke(packet) as? Boolean ?: booleanValues.single()
        check(reset == expectedReset) {
            "Expected reset=$expectedReset, got $reset from ${packet.javaClass.name}"
        }
        val remainingFlags = booleanValues.toMutableList()
        check(remainingFlags.remove(reset) && remainingFlags.none { it }) {
            "Expected non-reset packet flags to be false, got $booleanValues from ${packet.javaClass.name}"
        }

        val mapFields =
            fields
                .filter { Map::class.java.isAssignableFrom(it.type) }
                .map { it to ((it.read(packet) as? Map<*, *>) ?: error("${it.name} was not a map")) }
        val progressField =
            mapFields.singleOrNull { (field) ->
                field.genericClassArgument(1)?.name == "net.minecraft.advancements.AdvancementProgress"
            } ?: error("Expected exactly one typed progress map in ${packet.javaClass.name}")
        val progressById =
            progressField.second.entries.associate { (key, value) ->
                requireNotNull(key).identifierText() to requireNotNull(value) { "Progress value for $key was null" }
            }

        val addedMaps = mapFields.filter { it.first != progressField.first }
        val addedLists =
            fields.filter { Collection::class.java.isAssignableFrom(it.type) && !Set::class.java.isAssignableFrom(it.type) }
        check(addedMaps.size + addedLists.size == 1) {
            "Expected exactly one added-definition container in ${packet.javaClass.name}"
        }
        val serializedBuilders = addedMaps.isNotEmpty()
        val addedEntries =
            if (serializedBuilders) {
                addedMaps.single().second.entries.map { (key, value) ->
                    requireNotNull(key).identifierText() to requireNotNull(value) { "Added definition for $key was null" }
                }
            } else {
                val holders =
                    addedLists.single().read(packet) as? Collection<*>
                        ?: error("Added definitions were not a collection in ${packet.javaClass.name}")
                holders.map { value ->
                    val holder = requireNotNull(value) { "Added advancement holder was null" }
                    val definitionField =
                        instanceFields(holder.javaClass).singleOrNull { it.type.name == "net.minecraft.advancements.Advancement" }
                            ?: error("Expected one advancement value in ${holder.javaClass.name}")
                    holder.identifier() to requireNotNull(definitionField.read(holder)) { "Added advancement value was null" }
                }
            }
        val addedDefinitions = addedEntries.toMap()
        check(addedDefinitions.size == addedEntries.size) { "Duplicate advancement IDs in the added packet payload" }
        val removedField =
            fields.singleOrNull { Set::class.java.isAssignableFrom(it.type) }
                ?: error("Expected exactly one removed-ID set in ${packet.javaClass.name}")
        val removedIds =
            (removedField.read(packet) as? Set<*> ?: error("Removed IDs were not a set"))
                .map { requireNotNull(it).identifierText() }
                .toSet()

        check(addedDefinitions.keys == expectedAdded.map { it.id.toString() }.toSet()) {
            "Expected added=$expectedAdded, got ${addedDefinitions.keys} from ${packet.javaClass.name}"
        }
        check(removedIds == expectedRemoved.map { it.id.toString() }.toSet()) {
            "Expected removed=$expectedRemoved, got $removedIds from ${packet.javaClass.name}"
        }
        check(progressById.keys == expectedProgress.keys.map { it.id.toString() }.toSet()) {
            "Expected progress keys=${expectedProgress.keys}, got ${progressById.keys} from ${packet.javaClass.name}"
        }

        expectedAdded.forEach { advancement ->
            validateDefinition(addedDefinitions.getValue(advancement.id.toString()), advancement, serializedBuilders)
        }
        expectedProgress.forEach { (advancement, progress) ->
            validateProgress(
                progressById.getValue(advancement.id.toString()),
                expectedProgress = progress,
                requirement = advancement.requirement,
            )
        }
    }

    private fun validateProgress(
        progress: Any,
        expectedProgress: Int,
        requirement: Int,
    ) {
        validateRequirements(progress, requirement)
        val methods = progress.javaClass.declaredMethods.filter { it.parameterCount == 0 && !it.isSynthetic }
        val percent =
            methods
                .single { it.returnType == Float::class.javaPrimitiveType }
                .invokeAccessible(progress) as Float
        check(abs(percent - expectedProgress.toFloat() / requirement) < 0.0001F) {
            "Expected percent ${expectedProgress.toFloat() / requirement}, got $percent"
        }

        val expectedCompleted = (0 until expectedProgress).map { it.toString(36) }.toSet()
        val expectedRemaining = (expectedProgress until requirement).map { it.toString(36) }.toSet()
        val criteriaSets =
            methods
                // Component is also Iterable on newer releases, but getProgressText
                // legitimately returns null for one-criterion advancements.
                .filter { it.returnType == Iterable::class.java }
                .map {
                    (it.invokeAccessible(progress) as Iterable<*>)
                        .map(Any?::toString)
                        .toSet()
                }.toSet()
        check(criteriaSets == setOf(expectedCompleted, expectedRemaining)) {
            "Expected completed/remaining criteria $expectedCompleted / $expectedRemaining, got $criteriaSets"
        }

        val booleanValues =
            methods
                .filter { it.returnType == Boolean::class.javaPrimitiveType }
                .map { it.invokeAccessible(progress) as Boolean }
                .sorted()
        val expectedBooleanValues =
            when (expectedProgress) {
                0 -> listOf(false, false)
                requirement -> listOf(true, true)
                else -> listOf(false, true)
            }
        check(booleanValues == expectedBooleanValues) {
            "Expected done/hasProgress $expectedBooleanValues, got $booleanValues"
        }

        if (1 < requirement) {
            val progressText =
                methods
                    .asSequence()
                    .mapNotNull { runCatching { it.invokeAccessible(progress) }.getOrNull() }
                    .mapNotNull(::componentText)
                    .firstOrNull { it.matches(Regex("\\d+/\\d+")) }
            check(progressText == "$expectedProgress/$requirement") {
                "Expected progress text $expectedProgress/$requirement, got $progressText"
            }
        }
    }

    private fun validateDefinitions() {
        val convert =
            runtime.javaClass.declaredMethods.single {
                it.name == "convert" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single().name == KtAdvancement::class.java.name
            }
        TestAdvancement.entries.forEach { advancement ->
            val definition = requireNotNull(convert.invokeAccessible(runtime, advancement))
            validateDefinition(definition, advancement)
        }
    }

    private fun validateDefinition(
        definition: Any,
        expected: TestAdvancement,
        serializedBuilder: Boolean = false,
    ) {
        val fields = instanceFields(definition.javaClass)
        val criteriaField =
            fields.singleOrNull { Map::class.java.isAssignableFrom(it.type) }
                ?: error("Expected exactly one criteria map in ${definition.javaClass.name}")
        val criteria = criteriaField.read(definition) as? Map<*, *> ?: error("Definition criteria were not a map")
        val expectedCriteria = (0 until expected.requirement).map { it.toString(36) }.toSet()
        check(criteria.keys.toSet() == expectedCriteria && criteria.values.none { it == null }) {
            "Expected criteria $expectedCriteria in ${definition.javaClass.name}, got $criteria"
        }
        validateRequirements(definition, expected.requirement)

        val parent =
            when {
                definition.javaClass.isRecord -> {
                    val field = fields.singleOrNull { it.isOptionalOf(::isIdentifierType) }
                        ?: error("Expected one optional parent ID in ${definition.javaClass.name}")
                    field.optionalOrValue(definition)?.identifierText()
                }

                serializedBuilder -> {
                    // Legacy packets contain deconstructed builders, whose identifier is the parent ID.
                    val field = fields.singleOrNull { isIdentifierType(it.type) }
                        ?: error("Expected one serialized parent ID in ${definition.javaClass.name}")
                    field.read(definition)?.identifierText()
                }

                else -> {
                    val field = fields.singleOrNull { it.type == definition.javaClass }
                        ?: error("Expected one parent advancement in ${definition.javaClass.name}")
                    field.read(definition)?.identifier()
                }
            }
        check(parent == expected.parent?.id?.toString()) {
            "Expected parent ${expected.parent?.id} for ${expected.id}, got $parent"
        }
        validateDisplay(findDisplay(definition), expected.display)
    }

    private fun validateRequirements(
        owner: Any,
        requirement: Int,
    ) {
        val groups = requirementGroups(owner)
        val expectedCriteria = (0 until requirement).map { it.toString(36) }
        check(groups.size == requirement) {
            "Expected $requirement mandatory requirement groups in ${owner.javaClass.name}, got $groups"
        }
        check(groups.all { it.size == 1 }) {
            "Expected one criterion per mandatory group in ${owner.javaClass.name}, got $groups"
        }
        // Keep duplicates until this comparison: a set-only check would accept
        // repeated groups and could hide an incorrect denominator in the client.
        check(groups.map { it.single() }.sorted() == expectedCriteria.sorted()) {
            "Expected each criterion in $expectedCriteria exactly once in ${owner.javaClass.name}, got $groups"
        }
    }

    private fun requirementGroups(owner: Any): List<List<String>> {
        val candidates: List<Pair<String, Any?>> =
            allFields(owner.javaClass)
                .filterNot { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field ->
                    when {
                        isRequirementArray(field.type) -> field.name to field.read(owner)
                        field.type.isRecord -> {
                            // 1.20.2 wraps String[][] in a single-component record;
                            // 1.20.3+ uses a single List<List<String>> component.
                            // The structure, not mapped field/class names, identifies it.
                            val component =
                                allFields(field.type)
                                    .filterNot { Modifier.isStatic(it.modifiers) }
                                    .singleOrNull()
                            if (
                                component != null &&
                                (isRequirementArray(component.type) || List::class.java.isAssignableFrom(component.type))
                            ) {
                                val record = field.read(owner)
                                "${field.name}.${component.name}" to record?.let { component.read(it) }
                            } else {
                                null
                            }
                        }

                        else -> null
                    }
                }
        check(candidates.size == 1) {
            "Expected exactly one requirements container in ${owner.javaClass.name}, found ${candidates.map { it.first }}"
        }
        val (location, value) = candidates.single()
        val groups =
            when (value) {
                is Array<*> -> value.toList()
                is List<*> -> value
                else -> error("Requirements ${owner.javaClass.name}.$location were not an array or list")
            }
        return groups.mapIndexed { index, group ->
            val criteria =
                when (group) {
                    is Array<*> -> group.toList()
                    is List<*> -> group
                    else -> error("Requirement group $index in ${owner.javaClass.name}.$location was not an array or list")
                }
            criteria.map { criterion ->
                criterion as? String
                    ?: error("Requirement group $index in ${owner.javaClass.name}.$location contained a non-string criterion")
            }
        }
    }

    private fun isRequirementArray(type: Class<*>): Boolean =
        type.isArray && type.componentType.isArray && type.componentType.componentType == String::class.java

    private fun findDisplay(definition: Any): Any {
        val field =
            instanceFields(definition.javaClass).singleOrNull {
                isDisplayType(it.type) || it.isOptionalOf(::isDisplayType)
            } ?: error("Expected exactly one display field in ${definition.javaClass.name}")
        return requireNotNull(field.optionalOrValue(definition)) { "Display was absent in ${definition.javaClass.name}" }
    }

    private fun validateDisplay(
        display: Any,
        expected: KtAdvancement.Display,
    ) {
        val fields = instanceFields(display.javaClass)
        val floats = fields.filter { it.type == Float::class.javaPrimitiveType }.map { it.read(display) as Float }
        check(floats == listOf(expected.x, expected.y)) {
            "Expected display location ${expected.x},${expected.y}, got $floats from ${display.javaClass.name}"
        }

        val booleans = fields.filter { it.type == Boolean::class.javaPrimitiveType }.map { it.read(display) as Boolean }
        check(booleans == listOf(expected.showToast, false, false)) {
            "Expected display flags ${expected.showToast},false,false, got $booleans from ${display.javaClass.name}"
        }

        val texts =
            fields
                .filter {
                    it.type.packageName == "net.minecraft.network.chat" &&
                        it.type.simpleName in setOf("Component", "IChatBaseComponent")
                }.map { componentText(it.read(display)) }
        check(texts == listOf(expected.title, expected.description)) {
            "Expected display text '${expected.title}'/'${expected.description}', got $texts"
        }

        val frameField = fields.singleOrNull { it.type.isEnum }
            ?: error("Expected exactly one display frame in ${display.javaClass.name}")
        val frame = frameField.read(display) as? Enum<*> ?: error("Display frame was absent")
        check(frame.name.equals(expected.frame.name, ignoreCase = true)) {
            "Expected frame ${expected.frame}, got $frame"
        }

        val iconField =
            fields.singleOrNull {
                it.type.packageName == "net.minecraft.world.item" &&
                    it.type.simpleName in setOf("ItemStack", "ItemStackTemplate")
            } ?: error("Expected exactly one display icon in ${display.javaClass.name}")
        val icon = requireNotNull(iconField.read(display)) { "Display icon was absent" }
        val stack =
            if (icon.javaClass.simpleName == "ItemStackTemplate") {
                val create =
                    icon.javaClass.methods.singleOrNull {
                        !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                            it.returnType.name == "net.minecraft.world.item.ItemStack"
                    } ?: error("Expected exactly one ItemStackTemplate materializer in ${icon.javaClass.name}")
                requireNotNull(create.invokeAccessible(icon)) { "Materialized display icon was null" }
            } else {
                icon
            }
        val craftItemStack = Class.forName("${craftServerClass.packageName}.inventory.CraftItemStack")
        val asBukkitCopy =
            craftItemStack.methods.singleOrNull {
                it.name == "asBukkitCopy" && Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 && it.parameterTypes.single().name == "net.minecraft.world.item.ItemStack" &&
                    it.returnType == ItemStack::class.java
            } ?: error("Expected CraftItemStack.asBukkitCopy for ${stack.javaClass.name}")
        val bukkitIcon = asBukkitCopy.invoke(null, stack) as? ItemStack ?: error("Display icon could not be copied")
        check(bukkitIcon.type == expected.icon.type && bukkitIcon.amount == expected.icon.amount) {
            "Expected icon ${expected.icon.type} x${expected.icon.amount}, got ${bukkitIcon.type} x${bukkitIcon.amount}"
        }
        validateBackground(display, expected)
    }

    private fun validateBackground(
        display: Any,
        expected: KtAdvancement.Display,
    ) {
        val field =
            instanceFields(display.javaClass).singleOrNull {
                isBackgroundType(it.type) || it.isOptionalOf(::isBackgroundType)
            } ?: error("Expected exactly one background field in ${display.javaClass.name}")
        val background = field.optionalOrValue(display)
        val expectedBackground = expected.background
        check((background == null) == (expectedBackground == null)) {
            "Expected background $expectedBackground, got $background"
        }
        if (background == null || expectedBackground == null) return

        if (isIdentifierType(background.javaClass)) {
            check(background.identifierText() == expectedBackground.toString()) {
                "Expected background $expectedBackground, got $background"
            }
        } else {
            // 1.21.5+ separates the asset ID from its resolved texture path.
            check(isClientAssetType(background.javaClass)) { "Unsupported background type ${background.javaClass.name}" }
            fun assetIdentifier(name: String): String {
                val getter =
                    background.javaClass.methods.singleOrNull {
                        it.name == name && it.parameterCount == 0 && isIdentifierType(it.returnType)
                    } ?: error("Missing background $name accessor in ${background.javaClass.name}")
                return requireNotNull(getter.invokeAccessible(background)) { "Background $name was null" }.identifierText()
            }
            val expectedAsset =
                "${expectedBackground.namespace}:${expectedBackground.key.removePrefix("textures/").removeSuffix(".png")}"
            check(assetIdentifier("id") == expectedAsset && assetIdentifier("texturePath") == expectedBackground.toString()) {
                "Expected background asset $expectedAsset at $expectedBackground, got $background"
            }
        }
    }

    private fun isIdentifierType(type: Class<*>): Boolean =
        type.packageName == "net.minecraft.resources" &&
            type.simpleName in setOf("ResourceLocation", "MinecraftKey", "Identifier")

    private fun isDisplayType(type: Class<*>): Boolean =
        type.packageName == "net.minecraft.advancements" &&
            type.simpleName in setOf("DisplayInfo", "AdvancementDisplay")

    private fun isClientAssetType(type: Class<*>): Boolean =
        type.name == "net.minecraft.core.ClientAsset" ||
            (type.simpleName == "ResourceTexture" && type.enclosingClass?.name == "net.minecraft.core.ClientAsset")

    private fun isBackgroundType(type: Class<*>): Boolean = isIdentifierType(type) || isClientAssetType(type)

    private fun Any.identifierText(): String {
        check(isIdentifierType(javaClass)) { "Expected a namespaced identifier, got ${javaClass.name}" }
        return toString()
    }

    private fun Any.identifier(): String {
        val field =
            instanceFields(javaClass).singleOrNull { isIdentifierType(it.type) }
                ?: error("Expected exactly one identifier field in ${javaClass.name}")
        return requireNotNull(field.read(this)) { "Identifier was absent in ${javaClass.name}" }.identifierText()
    }

    private fun componentText(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value
            else ->
                value.javaClass.methods
                    .asSequence()
                    .filter { it.parameterCount == 0 && it.returnType == String::class.java && it.name != "toString" }
                    .sortedByDescending { it.name == "getString" }
                    .mapNotNull { runCatching { it.invoke(value) as? String }.getOrNull() }
                    .firstOrNull { it.isNotEmpty() }
        }

    private fun runtimeName(): String = runtime.javaClass.name

    private fun allFields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .toList()

    private fun instanceFields(type: Class<*>): List<Field> = allFields(type).filterNot { Modifier.isStatic(it.modifiers) }

    private fun Field.genericClassArgument(index: Int): Class<*>? =
        (genericType as? ParameterizedType)?.actualTypeArguments?.getOrNull(index) as? Class<*>

    private fun Field.isOptionalOf(predicate: (Class<*>) -> Boolean): Boolean =
        type == Optional::class.java && genericClassArgument(0)?.let(predicate) == true

    private fun Field.optionalOrValue(instance: Any): Any? =
        if (type == Optional::class.java) {
            (read(instance) as? Optional<*> ?: error("Optional field $name was null")).orElse(null)
        } else {
            read(instance)
        }

    private fun Field.read(instance: Any): Any? {
        trySetAccessible()
        return get(instance)
    }

    private fun Method.invokeAccessible(
        instance: Any,
        vararg arguments: Any?,
    ): Any? {
        trySetAccessible()
        return invoke(instance, *arguments)
    }

    private class RecordingStore : KtAdvancementStore<TestAdvancement> {
        private val progress = mutableMapOf<TestAdvancement, Int>()

        override fun getProgress(
            player: Player,
            advancements: List<TestAdvancement>,
        ) = progress.filterKeys(advancements::contains)

        override fun updateProgress(
            player: Player,
            progress: Map<TestAdvancement, Int>,
        ) {
            this.progress.putAll(progress)
        }
    }
}
