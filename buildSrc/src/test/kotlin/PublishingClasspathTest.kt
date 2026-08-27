import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PublishingClasspathTest {
    @Test
    fun `buildSrc serialization supports the Minecraft server plugin default method`() {
        // minecraft-server 4.0.2's generated serializers invoke this JVM default method.
        // Dokka's older transitive runtime must not shadow it in buildSrc's parent loader.
        val serializer = Class.forName("kotlinx.serialization.internal.GeneratedSerializer")

        assertTrue(
            serializer.getMethod("typeParametersSerializers").isDefault,
            "The buildSrc serialization runtime is incompatible with the Minecraft server plugin",
        )
    }
}
