/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher.util

import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.LinkedList

/**
 * All classes for the target app and any extension classes.
 */
internal class PatchClasses internal constructor(
    /**
     * Class type -> ClassDef.
     */
    internal val classMap: MutableMap<String, ClassDefWrapper>
) {

    /**
     * Container to hold the class definition that is either mutable or immutable.
     *
     * This intermediate container is needed to easily update the class in both
     * the class map and in the string map with a single constant time operation.
     */
    internal class ClassDefWrapper(
        /**
         * Can be immutable or mutable.
         */
        var classDef: ClassDef,
    ) {
        fun getMutableClass(): MutableClass {
            if (classDef !is MutableClass) {
                classDef = MutableClass(classDef)
            }
            return classDef as MutableClass
        }
    }

    /**
     * @return All strings found anywhere in all class methods.
     */
    private fun ClassDef.findMethodStrings(): List<String>? {
        var list : MutableList<String>? = null

        methods.forEach { method ->
            // Add strings contained in the method as the key.
            method.instructionsOrNull?.forEach { instruction ->
                val opcode = instruction.opcode
                if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) {
                    return@forEach
                }

                val string = ((instruction as ReferenceInstruction).reference as StringReference).string

                if (list == null) {
                    list = mutableListOf()
                }
                list.add(string)
            }
        }

        return list
    }

    /**
     * Opcode string constant -> List<ClassDefWrapper>
     */
    private var stringMap: Map<String, List<ClassDefWrapper>>? = null

    /**
     * All classes that contain at least 1 string.
     * Same contents as [stringMap] values except contains no duplicates.
     */
    private var allClassesWithStrings: List<ClassDefWrapper>? = null

    internal constructor(set: Set<ClassDef>) : this(set.map {
        ClassDefWrapper(it)
    }.associateByTo(
        // Must use linked hash map, otherwise with a regular map the ordering of classes found
        // in the apk is not preserved, and old fingerprints that have multiple matches can match
        // the wrong class due to hashmap random class iteration during matching. The issue is with
        // some fingerprint declarations not being unique enough and currently there is no way to
        // check for duplicate matches.
        // See https://github.com/ReVanced/revanced-patcher/issues/74
        //
        // Pre-size so rehashing doesn't occur and use a more performant load factor.
        LinkedHashMap(2 * set.size, 0.5f)
    ) { wrapper ->
        wrapper.classDef.type
    })

    internal fun close() {
        classMap.clear()
        closeStringMap()
    }

    internal fun closeStringMap() {
        stringMap = null
        allClassesWithStrings = null
        signatureMap = null
    }

    internal fun addClass(classDef: ClassDef) {
        classMap[classDef.type] = ClassDefWrapper(classDef)
    }

    /**
     * Method return type and parameter count -> the classes declaring such a method.
     *
     * A fingerprint with neither a string literal nor a defining class has nothing to narrow on, so
     * resolving it means walking every class. Many also constrain the return type and parameter
     * count of the method they are looking for, which is what this indexes.
     *
     * Expect a modest win rather than a large one. Only a fingerprint that declares parameters *and*
     * compares the return type for equality can use the index at all, and how much it narrows
     * depends on how common the signature is: the median bucket holds roughly an eighth of the
     * app's classes, but a signature as ordinary as `void` with no parameters matches nearly two
     * thirds of them. Everything that cannot use the index still walks every class, and on a
     * YouTube-sized target that full walk remains the larger half of fingerprint resolution.
     *
     * Built lazily and, exactly like [stringMap], not rebuilt when a patch adds a class. That is
     * safe because this only ever *narrows* a search: a caller that finds no match here still falls
     * back to walking every class, which sees whatever the class map holds at that moment. A stale
     * or incomplete index therefore costs a little speed and can never cause a wrong or missed
     * match.
     */
    private var signatureMap: Map<String, List<ClassDefWrapper>>? = null

    /** Key into [signatureMap], or null for a signature that cannot be indexed. */
    private fun signatureKey(returnType: String?, parameterCount: Int) =
        if (returnType == null) null else "$returnType/$parameterCount"

    private fun getSignatureMap(): Map<String, List<ClassDefWrapper>> {
        signatureMap?.let { return it }

        val map = HashMap<String, MutableList<ClassDefWrapper>>()
        classMap.values.forEach { wrapper ->
            var keys: MutableSet<String>? = null
            wrapper.classDef.methods.forEach { method ->
                val key = signatureKey(method.returnType, method.parameters.size) ?: return@forEach
                if (keys == null) keys = HashSet()
                keys!!.add(key)
            }
            keys?.forEach { key ->
                map.getOrPut(key) { ArrayList(1) } += wrapper
            }
        }

        signatureMap = map
        return map
    }

    /**
     * Classes declaring at least one method with the given return type and parameter count, in class
     * map order, or null when the signature cannot be indexed.
     */
    internal fun getClassesBySignature(returnType: String?, parameterCount: Int) =
        signatureKey(returnType, parameterCount)?.let { getSignatureMap()[it] }

    internal fun getClassesByStringMap(): Map<String, List<ClassDefWrapper>> {
        if (stringMap != null) {
            return stringMap!!
        }

        // Default 0.75f load factor works well and a lower value does not improve patching time.
        val map = HashMap<String, MutableList<ClassDefWrapper>>()
        val classesWithStrings = mutableListOf<ClassDefWrapper>()

        classMap.values.forEach { wrapper ->
            val methodStrings = wrapper.classDef.findMethodStrings()
            if (methodStrings != null) {
                methodStrings.forEach { stringLiteral ->
                    val list = map.getOrPut(stringLiteral) {
                        ArrayList(1)
                    }
                    if (!list.contains(wrapper)) {
                        list += wrapper
                    }
                }

                classesWithStrings += wrapper
            }
        }

        stringMap = map
        allClassesWithStrings = classesWithStrings
        return map
    }

    internal fun getClassesFromOpcodeStringLiteral(stringLiteral: String): List<ClassDefWrapper>? {
        return getClassesByStringMap()[stringLiteral]
    }

    internal fun getAllClassesWithStrings(): List<ClassDefWrapper> {
        getClassesByStringMap() // Load string map if needed.
        return allClassesWithStrings!!
    }

    /**
     * Iterate over all classes.
     */
    fun forEach(action: (ClassDef) -> Unit) {
        classMap.values.forEach { wrapper ->
            action(wrapper.classDef)
        }
    }

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassBy
     */
    fun classByOrNull(classType: String) = classMap[classType]?.classDef

    private fun mapWrapperByOrNull(predicate: (ClassDef) -> Boolean) =
        classMap.values.find { wrapper ->
            predicate(wrapper.classDef)
        }

    /**
     * Find a class with a predicate. If you know the class type name,
     * it is highly preferred to instead use [classByOrNull(String)].
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type, or null if not found.
     */
    fun classByOrNull(predicate: (ClassDef) -> Boolean) = mapWrapperByOrNull(predicate)?.classDef

    /**
     * Find a class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return An immutable instance of the class type.
     */
    fun classBy(predicate: (ClassDef) -> Boolean) = classByOrNull(predicate)
        ?: throw PatchException("Could not find any class match")

    /**
     * Find a class with a predicate.
     *
     * @param classType The full classname.
     * @return An immutable instance of the class type.
     * @see mutableClassBy
     */
    fun classBy(classType: String) = classByOrNull(classType)
        ?: throw PatchException("Could not find class: $classType")

    /**
     * Mutable class from a full class name.
     * Returns `null` if class is not available, such as a built in Android or Java library.
     *
     * @param classDefType The full classname.
     * @return A mutable version of the class type.
     */
    fun mutableClassByOrNull(classDefType: String): MutableClass? {
        val wrapper = classMap[classDefType] ?: return null
        return wrapper.getMutableClass()
    }

    /**
     * Find a class with a predicate.
     *
     * @param classDefType The full classname.
     * @return A mutable version of the class type.
     */
    fun mutableClassBy(classDefType: String) = mutableClassByOrNull(classDefType)
        ?: throw PatchException("Could not find class: $classDefType")

    /**
     * Find a mutable class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassByOrNull(predicate: (ClassDef) -> Boolean) =
        mapWrapperByOrNull(predicate)?.getMutableClass()

    /**
     * @param classDef An immutable class.
     * @return A mutable version of the class definition.
     */
    fun mutableClassBy(classDef: ClassDef) =
        if (classDef is MutableClass) classDef else mutableClassBy(classDef.type)

    /**
     * Find a mutable class with a predicate.
     *
     * @param predicate A predicate to match the class.
     * @return A mutable class that matches the predicate.
     */
    fun mutableClassBy(predicate: (ClassDef) -> Boolean) = mutableClassByOrNull(predicate)
        ?: throw PatchException("Could not find any class match")
}
