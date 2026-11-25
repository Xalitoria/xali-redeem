import net.xalitoria.redeem.XaliRedeemApi
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ensures `redeem/api` exposes only JDK, Bukkit and `net.xalitoria.redeem` types so other plugins with their own
 * Kotlin runtime never hit a LinkageError.
 */
class AddonSurface {
    private val root: File = File(XaliRedeemApi::class.java.protectionDomain.codeSource.location.toURI())

    private val classes: List<Class<*>> = File(root, "net/xalitoria/redeem").walkTopDown()
        .filter { it.extension == "class" }
        .map { Class.forName(it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')) }
        .toList()

    private fun foreign(type: Class<*>): Boolean {
        var element = type
        while (element.isArray) element = element.componentType
        return element.name.startsWith("kotlin.") || element.name.startsWith("kotlinx.")
    }

    @Test
    fun noKotlinTypeInAnySignature() {
        assertTrue(classes.size >= 3, "the classes of the API are found")
        val found = buildList {
            for (type in classes) {
                for (method in type.declaredMethods) {
                    if (!Modifier.isPublic(method.modifiers) && !Modifier.isProtected(method.modifiers)) continue
                    if (method.isSynthetic && method.name.startsWith("access\$")) continue
                    if (foreign(method.returnType) || method.parameterTypes.any(::foreign)) add("${type.name}.${method.name}")
                }
                for (constructor in type.declaredConstructors) {
                    if (!Modifier.isPublic(constructor.modifiers) && !Modifier.isProtected(constructor.modifiers)) continue
                    if (type.name.endsWith("\$Companion")) continue
                    if (constructor.parameterTypes.any(::foreign)) add("${type.name}.<init>")
                }
                for (field in type.declaredFields) {
                    if (!Modifier.isPublic(field.modifiers) && !Modifier.isProtected(field.modifiers)) continue
                    if (foreign(field.type)) add("${type.name}.${field.name}")
                }
                if (type.superclass?.let(::foreign) == true || type.interfaces.any(::foreign)) add("${type.name} extends a Kotlin type")
            }
        }
        assertEquals(emptyList(), found)
    }

    @Test
    fun noDefaultImplementationsOfInterfaceMethods() {
        assertEquals(emptyList(), classes.filter { it.name.endsWith("\$DefaultImpls") }.map { it.name })
    }
}
