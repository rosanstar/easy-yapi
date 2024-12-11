package com.itangcent.idea.plugin.api.export.yapi

import com.google.inject.Inject
import com.intellij.psi.*
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.PsiUtil
import com.itangcent.common.constant.Attrs
import com.itangcent.common.utils.sub
import com.itangcent.common.utils.KV
import com.itangcent.common.utils.asKV
import com.itangcent.common.utils.notNullOrBlank
import com.itangcent.idea.plugin.api.export.ClassExportRuleKeys
import com.itangcent.idea.utils.CustomizedPsiClassHelper
import com.itangcent.intellij.config.ConfigReader
import com.itangcent.intellij.config.rule.computer
import com.itangcent.intellij.context.ActionContext
import com.itangcent.intellij.extend.guice.PostConstruct
import com.itangcent.intellij.jvm.duck.ArrayDuckType
import com.itangcent.intellij.jvm.duck.DuckType
import com.itangcent.intellij.jvm.duck.SingleDuckType
import com.itangcent.intellij.jvm.duck.SingleUnresolvedDuckType
import com.itangcent.intellij.jvm.element.ExplicitClass
import com.itangcent.intellij.jvm.element.ExplicitElement
import com.itangcent.intellij.jvm.element.ExplicitParameter
import com.itangcent.intellij.jvm.standard.StandardJvmClassHelper
import com.itangcent.intellij.psi.*
import com.itangcent.intellij.psi.JsonOption.has
import com.itangcent.intellij.util.Magics
import org.apache.commons.lang3.exception.ExceptionUtils
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * support rules:
 * 1. field.mock
 * 2. field.demo
 */
class YapiPsiClassHelper : CustomizedPsiClassHelper() {

    @Inject(optional = true)
    private val configReader: ConfigReader? = null

    private var resolveProperty: Boolean = true

    @PostConstruct
    fun initYapiInfo() {
        val contextSwitchListener: ContextSwitchListener? = ActionContext.getContext()
                ?.instance(ContextSwitchListener::class)
        contextSwitchListener!!.onModuleChange {
            val resolveProperty = configReader!!.first("field.mock.resolveProperty")
            if (!resolveProperty.isNullOrBlank()) {
                this.resolveProperty = resolveProperty.toBoolean() ?: true
            }
        }
    }

    override fun afterParseFieldOrMethod(fieldName: String, fieldType: DuckType, fieldOrMethod: ExplicitElement<*>, resourcePsiClass: ExplicitClass, option: Int, kv: KV<String, Any?>) {
        //compute `field.mock`
        ruleComputer!!.computer(ClassExportRuleKeys.FIELD_MOCK, fieldOrMethod)
                ?.takeIf { !it.isBlank() }
                ?.let { if (resolveProperty) configReader!!.resolveProperty(it) else it }
                ?.let { mockInfo ->
                    kv.sub(Attrs.MOCK_ATTR)[fieldName] = mockInfo
                }

        //compute `field.demo`
        val demoValue = ruleComputer.computer(YapiClassExportRuleKeys.FIELD_DEMO,
                fieldOrMethod)
        if (demoValue.notNullOrBlank()) {
            kv.sub(Attrs.EXAMPLE_ATTR)[fieldName] = demoValue
        }

        super.afterParseFieldOrMethod(fieldName, fieldType, fieldOrMethod, resourcePsiClass, option, kv)
    }

    fun getTypeObject(duckType: DuckType?, context: PsiElement, option: Int,parameter: ExplicitParameter): Any? {
        return doGetTypeObject(duckType, context, option,parameter).unwrapped { }
    }

    fun doGetTypeObject(duckType: DuckType?, context: PsiElement, option: Int,parameter: ExplicitParameter): Any? {
        actionContext!!.checkStatus()

        if (duckType == null) return null

        val cacheKey = duckType.canonicalText() + "@" + groupKey(context, option)

        val resolvedInfo = getResolvedInfo<Any>(cacheKey)
        if (resolvedInfo != null) {
            return resolvedInfo
        }

        val type = tryCastTo(duckType, context)

        if (type is ArrayDuckType) {
            val list = ArrayList<Any>()
            cacheResolvedInfo(cacheKey, list)
            doGetTypeObject(type.componentType(), context, option,parameter)?.let { list.add(it) }
            return Delay(list)
        }

        if (type is SingleDuckType) {
            return getTypeObject(type as SingleDuckType, context, option,parameter)
        }

        if (type is SingleUnresolvedDuckType) {
            return doGetTypeObject(type.psiType(), context, option,parameter)
        }

        return null
    }

    fun doGetTypeObject(psiType: PsiType?, context: PsiElement, option: Int,parameter: ExplicitParameter): Any? {
        actionContext!!.checkStatus()
        if (psiType == null || psiType == PsiType.NULL) return null
        val cacheKey = psiType.canonicalText + "@" + groupKey(context, option)

        val resolvedInfo = getResolvedInfo<Any>(cacheKey)
        if (resolvedInfo != null) {
            return resolvedInfo
        }

        val castTo = tryCastTo(psiType, context)
        when {
//            castTo == PsiType.NULL -> return null
//            castTo is PsiPrimitiveType -> return PsiTypesUtil.getDefaultValue(castTo)
            isNormalType(castTo) -> return getDefaultValue(castTo)
            castTo is PsiArrayType -> {   //array type
                val deepType = castTo.getDeepComponentType()
                val list = java.util.ArrayList<Any>()
                cacheResolvedInfo(cacheKey, list)//cache
                when {
                    deepType is PsiPrimitiveType -> list.add(PsiTypesUtil.getDefaultValue(deepType))
                    isNormalType(deepType) -> list.add(getDefaultValue(deepType) ?: "")
                    else -> doGetTypeObject(deepType, context, option,parameter)?.let { list.add(it) }
                }
                return Delay(list)
            }
            jvmClassHelper!!.isCollection(castTo) -> {   //list type
                val list = java.util.ArrayList<Any>()
                cacheResolvedInfo(cacheKey, list)//cache
                val iterableType = PsiUtil.extractIterableTypeParameter(castTo, false)
                val iterableClass = PsiUtil.resolveClassInClassTypeOnly(iterableType)
                val classTypeName: String? = iterableClass?.qualifiedName
                when {
                    classTypeName != null && isNormalType(iterableClass) -> getDefaultValue(iterableClass)?.let {
                        list.add(
                                it
                        )
                    }
                    iterableType != null -> doGetTypeObject(iterableType, context, option,parameter)?.let { list.add(it) }
                }
                return Delay(list)
            }
            jvmClassHelper.isMap(castTo) -> {   //list type
                val map: HashMap<Any, Any?> = HashMap()
                cacheResolvedInfo(cacheKey, map)//cache
                val keyType = PsiUtil.substituteTypeParameter(castTo, CommonClassNames.JAVA_UTIL_MAP, 0, false)
                val valueType = PsiUtil.substituteTypeParameter(castTo, CommonClassNames.JAVA_UTIL_MAP, 1, false)

                var defaultKey: Any? = null
                if (keyType != null) {
                    defaultKey = if (keyType == psiType) {
                        "nested type"
                    } else {
                        doGetTypeObject(keyType, context, option,parameter)
                    }
                }
                if (defaultKey == null) defaultKey = ""

                var defaultValue: Any? = null
                if (valueType != null) {
                    defaultValue = if (valueType == psiType) {
                        Collections.emptyMap<Any, Any>()
                    } else {
                        doGetTypeObject(valueType, context, option,parameter)
                    }
                }
                if (defaultValue == null) defaultValue = null

                map[defaultKey] = defaultValue

                return Delay(map)
            }
            jvmClassHelper.isEnum(psiType) -> {
                return parseEnum(psiType, context, option)
            }
            else -> {
                val typeCanonicalText = castTo.canonicalText
                if (typeCanonicalText.contains('<') && typeCanonicalText.endsWith('>')) {

                    val duckType = duckTypeHelper!!.resolve(castTo, context)

                    return when {
                        duckType != null -> {
                            val result = doGetTypeObject(duckType, context, option,parameter)
                            cacheResolvedInfo(cacheKey, result)
                            Delay(result)
                        }
                        else -> null
                    }
                } else {
                    val paramCls = PsiUtil.resolveClassInClassTypeOnly(castTo) ?: return null
                    if (ruleComputer!!.computer(ClassRuleKeys.TYPE_IS_FILE, paramCls) == true) {
                        cacheResolvedInfo(cacheKey, Magics.FILE_STR)//cache
                        return Magics.FILE_STR
                    }
                    return try {
                        val result = getFields(paramCls, option)
                        cacheResolvedInfo(cacheKey, result)
                        Delay(result)
                    } catch (e: Throwable) {
                        logger!!.error("error to getTypeObject:$psiType")
                        logger.trace(ExceptionUtils.getStackTrace(e))
                        null
                    }
                }
            }
        }
    }

    protected fun doGetFields(clsWithParam: SingleDuckType, context: PsiElement?, option: Int,parameter: ExplicitParameter): Any? {
        actionContext!!.checkStatus()

        val cacheKey = clsWithParam.canonicalText() + "@" + groupKey(context, option)

        val resolvedInfo = getResolvedInfo<Any>(cacheKey)
        if (resolvedInfo != null) {
            return resolvedInfo.asKV()
        }

        val psiClass = if (option.has(JsonOption.READ_COMMENT)) {
            getResourceClass(clsWithParam.psiClass())
        } else {
            clsWithParam.psiClass()
        }
        val kv: KV<String, Any?> = WrappedKV()
        cacheResolvedInfo(cacheKey, kv)
        beforeParseType(psiClass, clsWithParam, option, kv)

        val explicitClass = duckTypeHelper!!.explicit(getResourceType(clsWithParam))
        foreachField(explicitClass, option) { fieldName, fieldType, fieldOrMethod ->

            if (!beforeParseFieldOrMethod(
                            fieldName,
                            fieldType,
                            fieldOrMethod,
                            explicitClass,
                            option,
                            kv,parameter.containMethod().name()
                    )
            ) {
                onIgnoredParseFieldOrMethod(fieldName, fieldType, fieldOrMethod, explicitClass, option, kv)
                return@foreachField
            }

            if (!kv.contains(fieldName)) {

                parseFieldOrMethod(fieldName, fieldType, fieldOrMethod, explicitClass, option, kv)

                afterParseFieldOrMethod(fieldName, fieldType, fieldOrMethod, explicitClass, option, kv)
            }
        }

        afterParseType(psiClass, clsWithParam, option, kv)

        return Delay(kv)
    }

     fun getTypeObject(duckType: SingleDuckType?, context: PsiElement, option: Int,parameter: ExplicitParameter): Any? {
        actionContext!!.checkStatus()
        if (duckType == null) return null

        val cacheKey = duckType.canonicalText() + "@" + groupKey(context, option)

        val resolvedInfo = getResolvedInfo<Any>(cacheKey)
        if (resolvedInfo != null) {
            return resolvedInfo
        }

        val psiClass = if (option.has(JsonOption.READ_COMMENT)) {
            getResourceClass(duckType.psiClass())
        } else {
            duckType.psiClass()
        }

        when {
            isNormalType(duckType) -> //normal Type
                return getDefaultValue(duckType)
            jvmClassHelper!!.isCollection(duckType) -> {   //list type
                val list = ArrayList<Any>()

                cacheResolvedInfo(cacheKey, list)

                val realIterableType = findRealIterableType(duckType)
                if (realIterableType != null) {
                    doGetTypeObject(realIterableType, context, option,parameter)?.let { list.add(it) }
                }

                return Delay(list)
            }
            jvmClassHelper.isMap(duckType) -> {

                val typeOfCls = duckTypeHelper!!.buildPsiType(duckType, context)

                //map type
                val map: HashMap<Any, Any?> = HashMap()
                cacheResolvedInfo(cacheKey, map)
                val keyType = PsiUtil.substituteTypeParameter(typeOfCls, CommonClassNames.JAVA_UTIL_MAP, 0, false)
                val valueType = PsiUtil.substituteTypeParameter(typeOfCls, CommonClassNames.JAVA_UTIL_MAP, 1, false)

                var defaultKey: Any? = null

                if (keyType == null) {
                    val realKeyType = duckType.genericInfo?.get(StandardJvmClassHelper.KEY_OF_MAP)
                    defaultKey = doGetTypeObject(realKeyType, context, option,parameter)
                }

                if (defaultKey == null) {
                    defaultKey = if (keyType != null) {
                        doGetTypeObject(
                                duckTypeHelper.ensureType(keyType, duckType.genericInfo),
                                context,
                                option,parameter
                        )
                    } else {
                        ""
                    }
                }

                var defaultValue: Any? = null

                if (valueType == null) {
                    val realValueType = duckType.genericInfo?.get(StandardJvmClassHelper.VALUE_OF_MAP)
                    defaultValue = doGetTypeObject(realValueType, context, option,parameter)
                }
                if (defaultValue == null) {
                    defaultValue = if (valueType == null) {
                        null
                    } else {
                        doGetTypeObject(
                                duckTypeHelper.ensureType(valueType, duckType.genericInfo),
                                context,
                                option,parameter
                        )
                    }
                }

                if (defaultKey != null) {
                    map[defaultKey] = defaultValue
                }

                return Delay(map)
            }
            jvmClassHelper.isEnum(duckType) -> {
                return parseEnum(duckType, context, option)
            }
            else -> //class type
            {
                if (psiClass is PsiTypeParameter) {
                    val typeParams = duckType.genericInfo
                    if (typeParams != null) {
                        val realType = typeParams[psiClass.name]
                        if (realType != null) {
                            return doGetTypeObject(realType, context, option,parameter)
                        }
                    }
                }

                if (ruleComputer!!.computer(ClassRuleKeys.TYPE_IS_FILE, psiClass) == true) {
                    return Magics.FILE_STR
                }
                return doGetFields(duckType, context, option,parameter)
            }
        }
    }

}