package com.h0tk3y.kotlin.staticObjectNotation.analysis

import com.h0tk3y.kotlin.staticObjectNotation.analysis.AssignmentResolution.*
import com.h0tk3y.kotlin.staticObjectNotation.language.AccessChain
import com.h0tk3y.kotlin.staticObjectNotation.language.PropertyAccess
import com.h0tk3y.kotlin.staticObjectNotation.language.asChainOrNull

interface PropertyAccessResolver {
    fun doResolvePropertyAccessToObjectOrigin(
        analysisContext: AnalysisContext,
        propertyAccess: PropertyAccess
    ): ObjectOrigin?

    fun doResolvePropertyAccessToAssignable(
        analysisContext: AnalysisContext,
        propertyAccess: PropertyAccess
    ): PropertyReferenceResolution?
}

class PropertyAccessResolverImpl(
    private val expressionResolver: ExpressionResolver
) : PropertyAccessResolver {
    override fun doResolvePropertyAccessToObjectOrigin(
        analysisContext: AnalysisContext,
        propertyAccess: PropertyAccess
    ): ObjectOrigin? =
        analysisContext.doResolvePropertyAccessToObject(propertyAccess)

    override fun doResolvePropertyAccessToAssignable(
        analysisContext: AnalysisContext,
        propertyAccess: PropertyAccess
    ): PropertyReferenceResolution? =
        analysisContext.doResolvePropertyAccessToAssignableReference(propertyAccess)

    private fun AnalysisContext.doResolvePropertyAccessToAssignableReference(
        propertyAccess: PropertyAccess
    ): PropertyReferenceResolution? {
        val candidates = sequence {
            runPropertyAccessResolution(
                propertyAccess,
                onLocalValue = { yield(ReassignLocalVal(it.localValue)) },
                onProperty = { yield(AssignProperty(PropertyReferenceResolution(it.receiver, it.property))) },
                onExternalObject = { yield(ReassignExternal(it)) }
            )
        }

        return when (val firstMatch = candidates.firstOrNull()) {
            null -> return null
            is ReassignLocalVal -> {
                errorCollector(ResolutionError(propertyAccess, ErrorReason.ValReassignment(firstMatch.localValue)))
                null
            }

            is ReassignExternal -> {
                errorCollector(ResolutionError(propertyAccess, ErrorReason.ExternalReassignment(firstMatch.external)))
                null
            }

            is AssignProperty -> firstMatch.propertyReference
        }
    }

    private fun AnalysisContext.doResolvePropertyAccessToObject(
        propertyAccess: PropertyAccess
    ): ObjectOrigin? {
        val candidates: Sequence<ObjectOrigin> = sequence {
            runPropertyAccessResolution(
                propertyAccess = propertyAccess,
                onLocalValue = { yield(it) },
                onProperty = { yield(it) },
                onExternalObject = { yield(it) }
            )
        }
        return candidates.firstOrNull().also {
            if (it == null) {
                errorCollector(ResolutionError(propertyAccess, ErrorReason.UnresolvedReference(propertyAccess)))
            }
        }
    }

    private inline fun AnalysisContext.runPropertyAccessResolution(
        propertyAccess: PropertyAccess,
        onLocalValue: (ObjectOrigin.FromLocalValue) -> Unit,
        onProperty: (ObjectOrigin.PropertyReference) -> Unit,
        onExternalObject: (ObjectOrigin.External) -> Unit
    ) {
        when (propertyAccess.receiver) {
            null -> resolveUnqualifiedPropertyAccess(
                propertyAccess = propertyAccess,
                onLocalValue = onLocalValue,
                onProperty = onProperty,
                onExternal = onExternalObject
            )

            else -> doResolveQualifiedPropertyAccess(
                propertyAccess,
                onProperty = onProperty,
                onExternalObject = onExternalObject,
            )
        }
    }

    private inline fun AnalysisContext.doResolveQualifiedPropertyAccess(
        propertyAccess: PropertyAccess,
        onProperty: (ObjectOrigin.PropertyReference) -> Unit,
        onExternalObject: (ObjectOrigin.External) -> Unit
    ) {
        require(propertyAccess.receiver != null) { "Property access with explicit receiver expected" }

        val propertyName = propertyAccess.name

        val receiverOrigin = expressionResolver.doResolveExpression(this, propertyAccess.receiver)
        if (receiverOrigin != null) {
            val property = findDataProperty(getDataType(receiverOrigin), propertyName)
            if (property != null) {
                onProperty(ObjectOrigin.PropertyReference(receiverOrigin, property, propertyAccess))
            }
        }
        val asChainOrNull = propertyAccess.asChainOrNull()
        if (asChainOrNull != null) {
            val externalObject = schema.externalObjectsByFqName[asChainOrNull.asFqName()]
            if (externalObject != null) {
                onExternalObject(ObjectOrigin.External(externalObject, propertyAccess))
            }
        }
    }

    private inline fun AnalysisContextView.resolveUnqualifiedPropertyAccess(
        propertyAccess: PropertyAccess,
        onLocalValue: (ObjectOrigin.FromLocalValue) -> Unit,
        onProperty: (ObjectOrigin.PropertyReference) -> Unit,
        onExternal: (ObjectOrigin.External) -> Unit
    ) {
        require(propertyAccess.receiver == null) { "Name-only property access is expected" }

        lookupNamedValueInScopes(propertyAccess, onLocalValue, onProperty)

        if (propertyAccess.name in imports) {
            val externalObject = schema.externalObjectsByFqName[imports[propertyAccess.name]]
            if (externalObject != null) {
                onExternal(ObjectOrigin.External(externalObject, propertyAccess))
            }
        }
    }

    private inline fun AnalysisContextView.lookupNamedValueInScopes(
        propertyAccess: PropertyAccess,
        onLocalValue: (ObjectOrigin.FromLocalValue) -> Unit,
        onProperty: (ObjectOrigin.PropertyReference) -> Unit
    ) {
        currentScopes.asReversed().forEach { scope ->
            val localValue = scope.findLocalAsObjectOrigin(propertyAccess.name)
            if (localValue != null) {
                onLocalValue(localValue)
            }
            val scopeReceiver = scope.receiver
            val property = findDataProperty(getDataType(scopeReceiver), propertyAccess.name)
            if (property != null) {
                onProperty(ObjectOrigin.PropertyReference(scopeReceiver, property, propertyAccess))
            }
        }
    }

    private fun AnalysisScopeView.findLocalAsObjectOrigin(name: String): ObjectOrigin.FromLocalValue? {
        val local = findLocal(name) ?: return null
        val fromLocalValue = ObjectOrigin.FromLocalValue(local.localValue, local.assignment)
        return fromLocalValue
    }

    private fun findDataProperty(
        receiverType: DataType, name: String
    ): DataProperty? =
        if (receiverType is DataType.DataClass<*>) receiverType.properties.find { it.name == name } else null
}

private fun AccessChain.asFqName(): FqName = FqName(nameParts.dropLast(1).joinToString("."), nameParts.last())