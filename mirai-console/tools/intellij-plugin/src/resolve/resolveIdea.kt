/*
 * Copyright 2019-2022 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */

package net.mamoe.mirai.console.intellij.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import net.mamoe.mirai.console.compiler.common.resolve.COMPOSITE_COMMAND_SUB_COMMAND_FQ_NAME
import net.mamoe.mirai.console.compiler.common.resolve.SIMPLE_COMMAND_HANDLER_COMMAND_FQ_NAME
import net.mamoe.mirai.console.compiler.common.resolve.findParent
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.idea.references.KtSimpleNameReference
import org.jetbrains.kotlin.idea.references.resolveToDescriptors
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.typeUtil.supertypes
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance


/**
 * For CompositeCommand.SubCommand
 */
fun KtNamedFunction.isCompositeCommandSubCommand(): Boolean = this.hasAnnotation(COMPOSITE_COMMAND_SUB_COMMAND_FQ_NAME)
fun PsiModifierListOwner.isCompositeCommandSubCommand(): Boolean =
    this.hasAnnotation(COMPOSITE_COMMAND_SUB_COMMAND_FQ_NAME)

internal fun PsiModifierListOwner.hasAnnotation(fqName: FqName): Boolean = this.hasAnnotation(fqName.asString())

/**
 * SimpleCommand.Handler
 */
fun KtNamedFunction.isSimpleCommandHandler(): Boolean = this.hasAnnotation(SIMPLE_COMMAND_HANDLER_COMMAND_FQ_NAME)
fun PsiModifierListOwner.isSimpleCommandHandler(): Boolean = this.hasAnnotation(SIMPLE_COMMAND_HANDLER_COMMAND_FQ_NAME)

fun KtNamedFunction.isSimpleCommandHandlerOrCompositeCommandSubCommand(): Boolean =
    this.isSimpleCommandHandler() || this.isCompositeCommandSubCommand()

fun PsiModifierListOwner.isSimpleCommandHandlerOrCompositeCommandSubCommand(): Boolean =
    this.isSimpleCommandHandler() || this.isCompositeCommandSubCommand()


val KtPureClassOrObject.allSuperTypes: Sequence<KtSuperTypeListEntry>
    get() = sequence {
        yieldAll(superTypeListEntries)
        for (list in superTypeListEntries.asSequence()) {
            yieldAll(
                (list.typeAsUserType?.referenceExpression?.resolve()?.parents(true)?.filterIsInstance<KtClass>()
                    ?.firstOrNull())?.allSuperTypes.orEmpty()
            )
        }
    }

fun PsiElement.parents(withSelf: Boolean): Sequence<PsiElement> {
    val seed = if (withSelf) this else parentWithoutWalkingDirectories(this)
    return generateSequence(seed, ::parentWithoutWalkingDirectories)
}

private fun parentWithoutWalkingDirectories(element: PsiElement): PsiElement? {
    return if (element is PsiFile) null else element.parent
}

val PsiClass.allSuperTypes: Sequence<PsiClass>
    get() = sequence {
        interfaces.forEach {
            yield(it)
            yieldAll(it.allSuperTypes)
        }
        val superClass = superClass
        if (superClass != null) {
            yield(superClass)
            yieldAll(superClass.allSuperTypes)
        }
    }

fun KtConstructorCalleeExpression.getTypeAsUserType(): KtUserType? {
    val reference = typeReference
    if (reference != null) {
        val element = reference.typeElement
        if (element is KtUserType) {
            return element
        }
    }
    return null
}

fun KotlinType.hasSuperType(fqName: String, includeSelf: Boolean = true): Boolean {
    if (includeSelf && this.fqName?.asString() == fqName) return true
    return this.supertypes().any { it.hasSuperType("net.mamoe.mirai.message.data.Message", true) }
}

fun KtClassOrObject.hasSuperType(fqName: FqName): Boolean = allSuperNames.contains(fqName)
fun KtClass.hasSuperType(fqName: FqName): Boolean = allSuperNames.contains(fqName)

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@kotlin.internal.LowPriorityInOverloadResolution
fun PsiElement.hasSuperType(fqName: FqName): Boolean = allSuperNames.contains(fqName)

val KtClassOrObject.allSuperNames: Sequence<FqName> get() = allSuperTypes.mapNotNull { it.getKotlinFqName() }
val PsiClass.allSuperNames: Sequence<FqName>
    get() = allSuperTypes.mapNotNull { clazz ->
        clazz.qualifiedName?.let {
            FqName(
                it
            )
        }
    }

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@kotlin.internal.LowPriorityInOverloadResolution
val PsiElement.allSuperNames: Sequence<FqName>
    get() {
        return when (this) {
            is KtClassOrObject -> allSuperNames
            is PsiClass -> allSuperNames
            else -> emptySequence()
        }
    }

fun getElementForLineMark(callElement: PsiElement): PsiElement =
    when (callElement) {
        is KtSimpleNameExpression -> callElement.getReferencedNameElement()
        else ->
            // a fallback,
            //but who knows what to reference in KtArrayAccessExpression ?
            generateSequence(callElement, { it.firstChild }).last()
    }

val KtAnnotationEntry.annotationClass: KtClass?
    get() = calleeExpression?.constructorReferenceExpression?.run {
        try {
            resolve()
        } catch (e: Exception) {
            null // type inference with `by lazy {}` is unstable for now. I just ignore exceptions encountering with such issue.
        }
    }?.findParent<KtClass>()

fun KtAnnotated.hasAnnotation(fqName: FqName): Boolean =
    this.annotationEntries.any { it.annotationClass?.getKotlinFqName() == fqName }

fun ValueArgument.resolveStringConstantValues(bindingContext: BindingContext): Sequence<String>? {
    return this.getArgumentExpression()?.resolveStringConstantValues(bindingContext)
}

val PsiElement.allChildrenFlat: Sequence<PsiElement>
    get() {
        return sequence {
            for (child in children) {
                yield(child)
                yieldAll(child.allChildrenFlat)
            }
        }
    }

inline fun <reified E> PsiElement.findChild(): E? = this.children.find { it is E } as E?

fun KtValueArgument.type() = getArgumentExpression()?.referenceExpression()?.type()
fun KtExpression.resultingDescriptor() = resolveToCall(BodyResolveMode.PARTIAL)?.resultingDescriptor
fun KtExpression.type() = resultingDescriptor()?.returnType
fun KtReferenceExpression.typeFqName() = type()?.fqName
fun KtExpression.typeFqName() = referenceExpression()?.typeFqName()

fun KtElement.getResolvedCall(
    context: BindingContext,
): ResolvedCall<out CallableDescriptor>? {
    return this.getCall(context)?.getResolvedCall(context)
}

val ResolvedCall<out CallableDescriptor>.valueParameters: List<ValueParameterDescriptor> get() = this.resultingDescriptor.valueParameters

val Project.psiElementFactory: PsiElementFactory?
    get() = PsiElementFactory.getInstance(this)

fun ConstantValue<*>.selfOrChildrenConstantStrings(): Sequence<String> {
    return when (this) {
        is StringValue -> sequenceOf(value)
        is ArrayValue -> sequence {
            yieldAll(this@selfOrChildrenConstantStrings.selfOrChildrenConstantStrings())
        }
        else -> emptySequence()
    }
}

fun ClassDescriptor.findMemberFunction(name: Name, vararg typeProjection: TypeProjection): SimpleFunctionDescriptor? {
    return getMemberScope(typeProjection.toList()).getContributedFunctions(name, NoLookupLocation.FROM_IDE)
        .firstOrNull()
}

fun DeclarationDescriptor.companionObjectDescriptor(): ClassDescriptor? {
    if (this !is ClassDescriptor) {
        return null
    }
    return this.companionObjectDescriptor
}

fun KtExpression.resolveStringConstantValues(bindingContext: BindingContext): Sequence<String> {
    when (this) {
        is KtNameReferenceExpression -> {
            when (val descriptor =
                references.firstIsInstance<KtSimpleNameReference>().resolveToDescriptors(bindingContext)
                    .singleOrNull()) {
                is VariableDescriptor -> {
                    val compileTimeConstant = descriptor.compileTimeInitializer ?: return emptySequence()
                    return compileTimeConstant.selfOrChildrenConstantStrings()
                }
                //is PsiDeclarationStatement -> {
                //    // TODO: 2020/9/18 compile-time constants from Java
                //}
            }
        }
        is KtStringTemplateExpression -> {
            if (hasInterpolation()) return emptySequence()
            return sequenceOf(entries.joinToString("") { it.text })
        }
        /*
        is KtCallExpression -> {
            val callee = this.calleeExpression?.getResolvedCallOrResolveToCall(bindingContext)?.resultingDescriptor
            if (callee is VariableDescriptor) {
                val compileTimeConstant = callee.compileTimeInitializer ?: return null
                return compileTimeConstant.castOrNull<StringValue>()?.value
            }
            return null
        }*/
        is KtConstantExpression -> {
            // TODO: 2020/9/18  KtExpression.resolveStringConstantValue: KtConstantExpression
        }
    }
    return emptySequence()
}