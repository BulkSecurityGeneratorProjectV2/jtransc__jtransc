package com.jtransc.gen.haxe

import com.jtransc.annotation.JTranscKeep
import com.jtransc.annotation.haxe.*
import com.jtransc.ast.*
import com.jtransc.ast.feature.GotosFeature
import com.jtransc.ds.concatNotNull
import com.jtransc.error.InvalidOperationException
import com.jtransc.error.invalidOp
import com.jtransc.ffi.StdCall
import com.jtransc.gen.common.CommonGenGen
import com.jtransc.internal.JTranscAnnotationBase
import com.jtransc.lang.nullMap
import com.jtransc.lang.toBetterString
import com.jtransc.log.log
import com.jtransc.sourcemaps.Sourcemaps
import com.jtransc.text.Indenter
import com.jtransc.text.quote
import com.jtransc.util.sortDependenciesSimple
import com.jtransc.vfs.SyncVfsFile
import java.util.*

class GenHaxeGen(input: Input) : CommonGenGen(input) {
	companion object {
		//const val ENABLE_HXCPP_GOTO_HACK = true
		const val ENABLE_HXCPP_GOTO_HACK = false
	}

	val names = cnames as HaxeNames
	val JAVA_LANG_OBJECT = names.haxeName<java.lang.Object>()
	val JAVA_LANG_CLASS = names.haxeName<java.lang.Class<*>>()
	val JAVA_LANG_CLASS_name = names.getHaxeFieldName(java.lang.Class::class.java, "name")
	val JAVA_LANG_STRING = names.haxeName<java.lang.String>()
	val invocationHandlerHaxeName = names.haxeName<java.lang.reflect.InvocationHandler>()
	val methodHaxeName = names.haxeName<java.lang.reflect.Method>()
	val invokeHaxeName = AstMethodRef(java.lang.reflect.InvocationHandler::class.java.name.fqname, "invoke", types.build { METHOD(OBJECT, OBJECT, METHOD, ARRAY(OBJECT)) }).haxeName
	val toStringHaxeName = AstMethodRef(java.lang.Object::class.java.name.fqname, "toString", types.build { METHOD(STRING) }).haxeName
	val hashCodeHaxeName = AstMethodRef(java.lang.Object::class.java.name.fqname, "hashCode", types.build { METHOD(INT) }).haxeName
	val getClassHaxeName = AstMethodRef(java.lang.Object::class.java.name.fqname, "getClass", types.build { METHOD(CLASS) }).haxeName

	override fun genBody2WithFeatures(body: AstBody): Indenter {
		return if (ENABLE_HXCPP_GOTO_HACK && (tinfo.subtarget in setOf("cpp", "windows", "linux", "mac", "android"))) {
			features.apply(body, (featureSet + setOf(GotosFeature)), settings, types).genBody()
		} else {
			features.apply(body, featureSet, settings, types).genBody()
		}
	}

	internal fun _write(): GenHaxe.ProgramInfo {
		val vfs = srcFolder
		for (clazz in program.classes.filter { !it.isNative }) {
			if (clazz.implCode != null) {
				vfs[clazz.name.haxeFilePath] = clazz.implCode!!
			} else {
				writeClass(clazz, vfs)
			}
		}

		val copyFilesRaw = program.classes.flatMap { it.annotationsList.getTyped<HaxeAddFilesRaw>()?.value?.toList() ?: listOf() }
		val copyFilesTemplate = program.classes.flatMap { it.annotationsList.getTyped<HaxeAddFilesTemplate>()?.value?.toList() ?: listOf() }

		for (file in copyFilesRaw) vfs[file] = program.resourcesVfs[file]
		for (file in copyFilesTemplate) vfs[file] = templateString.gen(program.resourcesVfs[file].readString())

		val mainClassFq = program.entrypoint
		val mainClass = mainClassFq.haxeClassFqName
		val mainMethod = program[mainClassFq].getMethod("main", types.build { METHOD(VOID, ARRAY(STRING)) }.desc)!!.haxeName
		val entryPointClass = FqName(mainClassFq.fqname + "_EntryPoint")
		val entryPointFilePath = entryPointClass.haxeFilePath
		val entryPointFqName = entryPointClass.haxeGeneratedFqName
		val entryPointSimpleName = entryPointClass.haxeGeneratedSimpleClassName
		val entryPointPackage = entryPointFqName.packagePath

		fun calcClasses(program: AstProgram, mainClass: AstClass): List<AstClass> {
			return sortDependenciesSimple(mainClass) {
				it.classDependencies.map { program.get3(it) }
			}
		}

		fun inits() = Indenter.gen {
			line("HaxePolyfills.install();");
			line("haxe.CallStack.callStack();")
			line(names.getHaxeClassStaticInit(program[mainClassFq].ref, "program main"))
		}

		val customMain = program.allAnnotationsList.getTyped<HaxeCustomMain>()?.value

		val plainMain = Indenter.genString {
			line("package {{ entryPointPackage }};")
			line("class {{ entryPointSimpleName }}") {
				line("static public function main()") {
					line("{{ inits }}")
					line("{{ mainClass }}.{{ mainMethod }}(HaxeNatives.strArray(HaxeNatives.args()));")
				}
			}
		}

		log("Using ... " + if (customMain != null) "customMain" else "plainMain")

		templateString.setExtraData(mapOf(
			"entryPointPackage" to entryPointPackage,
			"entryPointSimpleName" to entryPointSimpleName,
			"mainClass" to mainClass,
			"mainClass2" to mainClassFq.fqname,
			"mainMethod" to mainMethod,
			"inits" to inits().toString()
		))
		vfs[entryPointFilePath] = templateString.gen(customMain ?: plainMain)

		vfs["HaxeReflectionInfo.hx"] = Indenter.genString {
			line("class HaxeReflectionInfo") {
				line("static public function __registerClasses()") {
					for (clazz in program.classes) {
						if (clazz.nativeName == null) {

							val availableOnTargets = clazz.annotationsList.getTyped<HaxeAvailableOnTargets>()?.value

							if (availableOnTargets != null) line("#if (" + availableOnTargets.joinToString(" || ") + ")")
							line("R.register(${clazz.ref.fqname.quote()}, ${clazz.ref.name.haxeClassFqName.quote()}, ${names.getHaxeClassStaticClassInit(clazz.ref)});")
							if (availableOnTargets != null) line("#end")
						}
					}
				}
			}
			//line(annotationProxyTypes)
		}

		return GenHaxe.ProgramInfo(entryPointClass, entryPointFilePath, vfs)
	}


	fun annotation(a: AstAnnotation): String {
		fun escapeValue(it: Any?): String {
			return when (it) {
				null -> "null"
				is AstAnnotation -> annotation(it)
				is Pair<*, *> -> escapeValue(it.second)
				is AstFieldRef -> it.containingTypeRef.name.haxeClassFqName + "." + it.haxeName
				is AstFieldWithoutTypeRef -> program[it.containingClass].ref.name.haxeClassFqName + "." + program.get(it).haxeName
				is String -> "HaxeNatives.boxString(${it.quote()})"
				is Boolean, is Byte, is Short, is Char, is Int, is Long, is Float, is Double -> names.escapeConstant(it)
				is List<*> -> "[" + it.map { escapeValue(it) }.joinToString(", ") + "]"
				is com.jtransc.org.objectweb.asm.Type -> "HaxeNatives.resolveClass(" + it.descriptor.quote() + ")"
				else -> invalidOp("GenHaxeGen.annotation.escapeValue: Don't know how to handle value ${it.javaClass.name} : ${it.toBetterString()} while generating $context")
			}
		}
		//val itStr = a.elements.map { it.key.quote() + ": " + escapeValue(it.value) }.joinToString(", ")
		val annotation = program.get3(a.type)
		val itStr = annotation.methods.map { escapeValue(if (it.name in a.elements) a.elements[it.name]!! else it.defaultTag) }.joinToString(", ")
		return "new ${names.getFullAnnotationProxyName(a.type)}([$itStr])"
	}

	fun annotationInit(a: AstAnnotation): List<AstType.REF> {
		fun escapeValue(it: Any?): List<AstType.REF> {
			return when (it) {
				null -> listOf()
				is AstAnnotation -> annotationInit(it)
				is Pair<*, *> -> escapeValue(it.second)
				is AstFieldRef -> listOf(it.containingTypeRef)
				is AstFieldWithoutTypeRef -> listOf(it.containingClass.ref())
				is List<*> -> it.flatMap { escapeValue(it) }
				else -> listOf()
			}
		}
		//val itStr = a.elements.map { it.key.quote() + ": " + escapeValue(it.value) }.joinToString(", ")
		val annotation = program.get3(a.type)
		return annotation.methods.flatMap {
			escapeValue(if (it.name in a.elements) a.elements[it.name]!! else it.defaultTag)
		}
	}

	fun visibleAnnotations(annotations: List<AstAnnotation>): String = "[" + annotations.filter { it.runtimeVisible }.map { annotation(it) }.joinToString(", ") + "]"
	fun visibleAnnotationsList(annotations: List<List<AstAnnotation>>): String = "[" + annotations.map { visibleAnnotations(it) }.joinToString(", ") + "]"

	fun annotationsInit(annotations: List<AstAnnotation>): Indenter {
		return Indenter.gen {
			for (i in annotations.filter { it.runtimeVisible }.flatMap { annotationInit(it) }.toHashSet()) {
				line(names.getHaxeClassStaticInit(i, "annotationsInit"))
			}
		}
	}

	fun dumpClassInfo(clazz: AstClass) = Indenter.genString {
		line("static public var HAXE_CLASS_NAME = ${clazz.name.fqname.quote()};")
		line("static public function HAXE_CLASS_INIT(c:$JAVA_LANG_CLASS = null):$JAVA_LANG_CLASS") {
			line("if (c == null) c = new $JAVA_LANG_CLASS();")
			line("c.$JAVA_LANG_CLASS_name = N.strLit(HAXE_CLASS_NAME);")
			//line("info(c, \"${clazz.name.haxeGeneratedFqName}\", " + (clazz.extending?.fqname?.quote() ?: "null") + ", [" + clazz.implementing.map { "\"${it.fqname}\"" }.joinToString(", ") + "], ${clazz.modifiers}, " + annotations(clazz.runtimeAnnotations) + ");")
			line(annotationsInit(clazz.runtimeAnnotations))
			val proxyClassName = if (clazz.isInterface) clazz.name.haxeGeneratedFqName.fqname + "." + clazz.name.haxeGeneratedSimpleClassName + "_Proxy" else "null"
			val ffiClassName = if (clazz.hasFFI) clazz.name.haxeGeneratedFqName.fqname + "." + clazz.name.haxeGeneratedSimpleClassName + "_FFI" else "null"
			line("R.i(c, ${clazz.name.haxeGeneratedFqName}, $proxyClassName, $ffiClassName, " + (clazz.extending?.fqname?.quote() ?: "null") + ", [" + clazz.implementing.map { "\"${it.fqname}\"" }.joinToString(", ") + "], ${clazz.modifiers}, " + visibleAnnotations(clazz.runtimeAnnotations) + ");")
			if (clazz.isVisible()) {
				for ((slot, field) in clazz.fields.withIndex()) {
					val internalName = field.haxeName
					if (field.isVisible()) {
						line("R.f(c, ${internalName.quote()}, $slot, \"${field.name}\", \"${field.descriptor}\", ${field.modifiers}, ${field.genericSignature.quote()}, ${visibleAnnotations(field.annotations)});");
					}
				}
				for ((slot, method) in clazz.methods.withIndex()) {
					val internalName = method.haxeName
					if (method.isVisible()) {
						if (method.name == "<init>") {
							line("R.c(c, ${internalName.quote()}, $slot, ${method.modifiers}, ${method.signature.quote()}, ${method.genericSignature.quote()}, ${visibleAnnotations(method.annotations)}, ${visibleAnnotationsList(method.parameterAnnotations)});");
						} else if (method.name == "<clinit>") {
						} else {
							line("R.m(c, ${method.id}, ${internalName.quote()}, $slot, \"${method.name}\", ${method.modifiers}, ${method.desc.quote()}, ${method.genericSignature.quote()}, ${visibleAnnotations(method.annotations)}, ${visibleAnnotationsList(method.parameterAnnotations)});");
						}
					}
				}
			}
			line("return c;")
		}
	}

	override fun genStm2(stm: AstStm): Indenter {
		this.stm = stm
		val program = program
		//val clazz = context.clazz
		val mutableBody = mutableBody
		return Indenter.gen {
			when (stm) {
			// c++ goto hack
				is AstStm.STM_LABEL -> line("untyped __cpp__('${stm.label.name}:');")
				is AstStm.GOTO -> line("untyped __cpp__('goto ${stm.label.name};');")
				is AstStm.IF_GOTO -> line("if (${stm.cond.genExpr()}) { untyped __cpp__('goto ${stm.label.name};'); }")
				is AstStm.SWITCH_GOTO -> {
					line("switch (${stm.subject.genExpr()})") {
						for ((value, label) in stm.cases) {
							line("case $value: untyped __cpp__('goto ${label.name};');");
						}
						line("default: untyped __cpp__('goto ${stm.default.name};');");
					}
				}
			// plain
				is AstStm.SET_NEW_WITH_CONSTRUCTOR -> {
					val newClazz = program[stm.target.name]
					//val mapping = mappings.getClassMapping(newClazz)
					refs.add(stm.target)
					val commaArgs = stm.args.map { it.genExpr() }.joinToString(", ")
					val className = stm.target.haxeTypeNew
					val localHaxeName = stm.local.nativeName

					if (newClazz.nativeName != null) {
						line("$localHaxeName = new $className($commaArgs);")
					} else {
						line("$localHaxeName = new $className();")
						line("$localHaxeName.${stm.method.haxeName}($commaArgs);")
					}
				}
				is AstStm.SET_FIELD_STATIC -> {
					refs.add(stm.clazz)
					mutableBody.initClassRef(fixField(stm.field).classRef, "SET_FIELD_STATIC")
					val left = fixField(stm.field).haxeStaticText
					val right = stm.expr.genExpr()
					if (left != right) {
						// Avoid: Assigning a value to itself
						line("$left /*${stm.field.name}*/ = $right;")
					}
				}

				is AstStm.SWITCH -> {
					line("switch (${stm.subject.genExpr()})") {
						for (case in stm.cases) {
							val value = case.first
							val caseStm = case.second
							line("case $value:")
							indent {
								line(caseStm.genStm())
							}
						}
						line("default:")
						indent {
							line(stm.default.genStm())
						}
					}
				}
				else -> line(super.genStm2(stm))
			}
		}
	}

	override fun N_ASET_T(type: AstType, array: String, index: String, value: String): String {
		val set = when (type) {
			AstType.BOOL -> "setBool"
			else -> "set"
		}
		return "$array.$set($index, $value);"
	}

	override fun genStmTryCatch(stm: AstStm.TRY_CATCH) = indent {
		line("try") {
			line(stm.trystm.genStm())
		}
		line("catch (J__i__exception__: Dynamic)") {
			line("J__exception__ = J__i__exception__;")
			line(stm.catch.genStm())
		}
	}

	override fun genStmRethrow(stm: AstStm.RETHROW) = indent { line("""throw J__i__exception__;""") }

	override fun genBodyLocal(local: AstLocal): Indenter = indent { line("var ${local.nativeName}: ${local.type.haxeTypeTag} = ${local.type.haxeDefaultString};") }
	override fun genBodyTrapsPrefix(): Indenter = indent { line("var J__exception__:Dynamic = null;") }
	override fun genBodyStaticInitPrefix(clazzRef: AstType.REF, reasons: ArrayList<String>) = indent {
		line(names.getHaxeClassStaticInit(clazzRef, reasons.joinToString(", ")))
	}

	class Strings {
		private var id = 0
		private val strings = hashMapOf<String, Int>()
		private val idsToString = hashMapOf<Int, String>()

		fun getIndices(): Set<Int> {
			return idsToString.keys
		}

		fun getStringWithId(id: Int): String {
			return idsToString[id]!!
		}

		fun getIndex(str: String): Int {
			if (str !in strings) {
				strings[str] = id
				idsToString[id] = str
				id++
			}
			return strings[str]!!
		}

		fun getId(str: String): String {
			return "__str" + getIndex(str)
		}
	}

	private var strings = Strings()

	override fun genExprThis(e: AstExpr.THIS): String = "this"
	override fun genLiteralString(v: String): String = strings.getId(v)

	override fun genExpr2(e: AstExpr): String {
		return when (e) {
			is AstExpr.BINOP -> {
				val resultType = e.type
				val l = e.left.genExpr()
				val r = e.right.genExpr()
				val opSymbol = e.op.symbol
				val opName = e.op.str

				val binexpr = if (resultType == AstType.LONG) {
					"N.l$opName($l, $r)"
				} else if (resultType == AstType.INT && opSymbol in setOf("/", "*", "<<", ">>", ">>>")) {
					"N.i$opName($l, $r)"
				} else {
					when (opSymbol) {
						"lcmp", "cmp", "cmpl", "cmpg", "==", "!=" -> "N.$opName($l, $r)"
						else -> "($l $opSymbol $r)"
					}
				}
				when (resultType) {
					AstType.INT -> "N.i($binexpr)"
					AstType.CHAR -> "N.i2c($binexpr)"
					AstType.SHORT -> "N.i2s($binexpr)"
					AstType.BYTE -> "N.i2b($binexpr)"
					else -> binexpr
				}
			}
			is AstExpr.CALL_BASE -> {
				// Determine method to call!
				val e2 = if (e.isSpecial && e is AstExpr.CALL_INSTANCE) AstExprUtils.RESOLVE_SPECIAL(program, e, context) else e
				val method = fixMethod(e2.method)
				val refMethod = program[method] ?: invalidOp("Can't find method: $method while generating $context")
				val refMethodClass = refMethod.containingClass
				val clazz = method.containingClassType
				val args = e2.args

				if (e2 is AstExpr.CALL_STATIC) {
					refs.add(clazz)
					mutableBody.initClassRef(clazz, "CALL_STATIC")
				}

				val isNativeCall = refMethodClass.isNative

				val commaArgs = args.map {
					if (isNativeCall) convertToHaxe(it) else it.genExpr()
				}.joinToString(", ")

				val base = when (e2) {
					is AstExpr.CALL_STATIC -> "${clazz.haxeTypeNew}"
					is AstExpr.CALL_SUPER -> "super"
					is AstExpr.CALL_INSTANCE -> "${e2.obj.genNotNull()}"
					else -> throw InvalidOperationException("Unexpected")
				}

				val result = "$base.${refMethod.haxeName}($commaArgs)"
				if (isNativeCall) convertToJava(refMethod.methodType.ret, result) else result
			}
			is AstExpr.FIELD_INSTANCE_ACCESS -> {
				"${e.expr.genNotNull()}.${fixField(e.field).haxeName}"
			}
			is AstExpr.FIELD_STATIC_ACCESS -> {
				refs.add(e.clazzName)
				mutableBody.initClassRef(fixField(e.field).classRef, "FIELD_STATIC_ACCESS")

				"${fixField(e.field).haxeStaticText}"
			}
			is AstExpr.ARRAY_LENGTH -> {
				val type = e.array.type
				if (type is AstType.ARRAY) {
					"(${e.array.genNotNull()}).length"
				} else {
					"cast(${e.array.genNotNull()}, ${names.HaxeArrayBase}).length"
				}
			}
			is AstExpr.ARRAY_ACCESS -> {
				val get = when (e.array.type.elementType) {
					AstType.BOOL -> "getBool"
					else -> "get"
				}
				"${e.array.genNotNull()}.$get(${e.index.genExpr()})"
			}

			is AstExpr.NEW -> {
				refs.add(e.target)
				val className = e.target.haxeTypeNew
				"new $className()"
			}
			is AstExpr.INSTANCE_OF -> {
				refs.add(e.checkType)
				"Std.is(${e.expr.genExpr()}, ${e.checkType.haxeTypeCast})"
			}
			is AstExpr.NEW_ARRAY -> {
				refs.add(e.type.elementType)
				val desc = e.type.mangle().replace('/', '.') // Internal to normal name!?
				when (e.counts.size) {
					1 -> {
						if (e.type.elementType !is AstType.Primitive) {
							"new ${names.HaxeArrayAny}(${e.counts[0].genExpr()}, \"$desc\")"
						} else {
							"new ${e.type.haxeTypeNew}(${e.counts[0].genExpr()})"
						}
					}
					else -> {
						"${names.HaxeArrayAny}.createMultiSure([${e.counts.map { it.genExpr() }.joinToString(", ")}], \"$desc\")"
					}
				}
			}
			is AstExpr.CAUGHT_EXCEPTION -> "J__exception__"
			is AstExpr.METHOD_CLASS -> {
				val methodInInterfaceRef = e.methodInInterfaceRef
				val methodToConvertRef = e.methodToConvertRef
				val interfaceName = methodInInterfaceRef.classRef.name

				val interfaceLambdaFqname = interfaceName.haxeLambdaName
				"new $interfaceLambdaFqname(" + Indenter.genString {
					//methodInInterfaceRef.type.args

					val argNameTypes = methodInInterfaceRef.type.args.map { it.haxeNameAndType }.joinToString(", ")

					line("function($argNameTypes)") {
						// @TODO: Static + non-static
						//val methodToCallClassName = methodToConvertRef.classRef.name.haxeClassFqName
						//val methodToCallName = methodToConvertRef.haxeName

						val args = methodInInterfaceRef.type.args.map { AstLocal(-1, it.name, it.type) }

						line("return " + genExpr2(AstExpr.CAST(AstExpr.CALL_STATIC(
							methodToConvertRef.containingClassType,
							methodToConvertRef,
							args.zip(methodToConvertRef.type.args).map { AstExpr.CAST(AstExpr.LOCAL(it.first), it.second.type) }
						), methodInInterfaceRef.type.ret)) + ";"
						)
					}
				} + ")"

			}
		//is AstExpr.REF -> genExpr2(e.expr)
			else -> super.genExpr2(e)
		}
	}

	fun convertToHaxe(expr: AstExpr.Box): String = convertToHaxe(expr.type, expr.genExpr())
	fun convertToJava(expr: AstExpr.Box): String = convertToJava(expr.type, expr.genExpr())
	fun convertToHaxe(type: AstType, text: String): String = convertToFromHaxe(type, text, toHaxe = true)
	fun convertToJava(type: AstType, text: String): String = convertToFromHaxe(type, text, toHaxe = false)

	fun convertToFromHaxe(type: AstType, text: String, toHaxe: Boolean): String {
		if (type is AstType.ARRAY) {
			return (if (toHaxe) "HaxeNatives.unbox($text)" else "cast(HaxeNatives.box($text), ${names.getNativeType(type, TypeKind.CAST)})")
		}

		if (type is AstType.REF) {
			val conversion = program[type.name].annotationsList.getTyped<HaxeNativeConversion>()
			if (conversion != null) {
				return (if (toHaxe) conversion.toHaxe else conversion.toJava).replace("@self", text)
			}
		}
		return text
	}

	override fun N_unboxBool(e: String) = "HaxeNatives.unboxBool($e)"
	override fun N_unboxByte(e: String) = "HaxeNatives.unboxByte($e)"
	override fun N_unboxShort(e: String) = "HaxeNatives.unboxShort($e)"
	override fun N_unboxChar(e: String) = "HaxeNatives.unboxChar($e)"
	override fun N_unboxInt(e: String) = "HaxeNatives.unboxInt($e)"
	override fun N_unboxLong(e: String) = "HaxeNatives.unboxLong($e)"
	override fun N_unboxFloat(e: String) = "HaxeNatives.unboxFloat($e)"
	override fun N_unboxDouble(e: String) = "HaxeNatives.unboxDouble($e)"
	override fun N_is(a: String, b: String) = "N.is($a, $b)"
	override fun N_z2i(str: String) = "N.z2i($str)"
	override fun N_i(str: String) = "(($str)|0)"
	override fun N_i2z(str: String) = "(($str)!=0)"
	override fun N_i2b(str: String) = "N.i2b($str)"
	override fun N_i2c(str: String) = "(($str)&0xFFFF)"
	override fun N_i2s(str: String) = "N.i2s($str)"
	override fun N_f2i(str: String) = "Std.int($str)"
	override fun N_i2i(str: String) = N_i(str)
	override fun N_i2j(str: String) = "HaxeNatives.intToLong($str)"
	override fun N_i2f(str: String) = "Math.fround(($str))"
	override fun N_i2d(str: String) = "($str)"
	override fun N_f2f(str: String) = "Math.fround($str)"
	override fun N_f2d(str: String) = "($str)"
	override fun N_d2f(str: String) = "Math.fround(($str))"
	override fun N_d2d(str: String) = "($str)"
	override fun N_d2i(str: String) = "Std.int($str)"
	override fun N_l2i(str: String) = "(($str).low)"
	override fun N_l2l(str: String) = "N.l2l($str)"
	override fun N_l2f(str: String) = "HaxeNatives.longToFloat($str)"
	override fun N_l2d(str: String) = "HaxeNatives.longToFloat($str)"
	override fun N_getFunction(str: String) = "HaxeNatives.getFunction($str)"
	override fun N_c(str: String, from: AstType, to: AstType) = "N.c($str, ${to.haxeTypeCast})"

	// @TODO: Use this.annotationsList.getTypedList
	private fun AstMethod.getHaxeNativeBodyList(): List<HaxeMethodBody> {
		val bodyList = this.annotationsList.getTyped<HaxeMethodBodyList>()
		val bodyEntry = this.annotationsList.getTyped<HaxeMethodBody>()
		val bodies = listOf(bodyList?.value?.toList(), listOf(bodyEntry)).concatNotNull()
		return bodies
	}

	private fun AstMethod.hasHaxeNativeBody(): Boolean = this.annotationsList.contains<HaxeMethodBodyList>() || this.annotationsList.contains<HaxeMethodBody>()

	private fun AstMethod.getHaxeNativeBody(defaultContent: Indenter): Indenter {
		val method = this

		val bodies = this.getHaxeNativeBodyList()

		return if (bodies.size > 0) {
			val pre = method.annotationsList.getTyped<HaxeMethodBodyPre>()?.value ?: ""
			val post = method.annotationsList.getTyped<HaxeMethodBodyPost>()?.value ?: ""

			val bodiesmap = bodies.map { it.target to it.value }.toMap()
			val defaultbody: Indenter = if ("" in bodiesmap) Indenter.gen { line(bodiesmap[""]!!) } else defaultContent
			val extrabodies = bodiesmap.filterKeys { it != "" }
			Indenter.gen {
				line(pre)
				if (extrabodies.size == 0) {
					line(defaultbody)
				} else {
					var first = true
					for ((target, extrabody) in extrabodies) {
						line((if (first) "#if" else "#elseif") + " ($target) $extrabody")
						first = false
					}
					line("#else")
					line(defaultbody)
					line("#end")
				}
				line(post)
			}
		} else {
			defaultContent
		}
	}

	fun writeClass(clazz: AstClass, vfs: SyncVfsFile) {
		context.clazz = clazz
		strings = Strings()

		val isRootObject = clazz.name.fqname == "java.lang.Object"
		val isInterface = clazz.isInterface
		val isAbstract = (clazz.classType == AstClassType.ABSTRACT)
		val isNormalClass = (clazz.classType == AstClassType.CLASS)
		val classType = if (isInterface) "interface" else "class"
		val simpleClassName = clazz.name.haxeGeneratedSimpleClassName
		fun getInterfaceList(keyword: String) = (if (clazz.implementing.isNotEmpty()) " $keyword " else "") + clazz.implementing.map { it.haxeClassFqName }.joinToString(" $keyword ")
		//val implementingString = getInterfaceList("implements")
		val isInterfaceWithStaticMembers = isInterface && clazz.fields.any { it.isStatic }
		//val isInterfaceWithStaticFields = clazz.name.withSimpleName(clazz.name.simpleName + "\$StaticMembers")
		refs._usedDependencies.clear()

		if (!clazz.extending?.fqname.isNullOrEmpty()) refs.add(AstType.REF(clazz.extending!!))
		for (impl in clazz.implementing) refs.add(AstType.REF(impl))
		//val interfaceClassName = clazz.name.append("_Fields");

		var output = arrayListOf<Pair<String, String>>()

		fun writeField(field: AstField, isInterface: Boolean): Indenter = Indenter.gen {
			val static = if (field.isStatic) "static " else ""
			val visibility = if (isInterface) " " else field.visibility.haxe
			val fieldType = field.type
			refs.add(fieldType)
			val defaultValue: Any? = if (field.hasConstantValue) field.constantValue else fieldType.haxeDefault
			val fieldName = field.haxeName
			//if (field.name == "this\$0") println("field: $field : fieldRef: ${field.ref} : $fieldName")
			if (!field.annotationsList.contains<HaxeRemoveField>()) {
				val keep = if (field.annotationsList.contains<JTranscKeep>()) "@:keep " else ""
				line("$keep$static$visibility var $fieldName:${fieldType.haxeTypeTag} = ${names.escapeConstant(defaultValue, fieldType)}; // /*${field.name}*/")
			}
		}

		fun writeMethod(method: AstMethod, isInterface: Boolean): Indenter {
			context.method = method
			return Indenter.gen {
				val static = if (method.isStatic) "static " else ""
				val visibility = if (isInterface) " " else method.visibility.haxe
				refs.add(method.methodType)
				val margs = method.methodType.args.map { it.name + ":" + it.type.haxeTypeTag }
				var override = if (method.haxeIsOverriding) "override " else ""
				val inline = if (method.isInline) "inline " else ""
				val rettype = if (method.methodVoidReturnThis) method.containingClass.astType else method.methodType.ret
				val decl = try {
					"$static $visibility $inline $override function ${method.haxeName}/*${method.name}*/(${margs.joinToString(", ")}):${rettype.haxeTypeTag}".trim()
				} catch (e: RuntimeException) {
					println("@TODO abstract interface not referenced: ${method.containingClass.fqname} :: ${method.name} : $e")
					//null
					throw e
				}

				if (isInterface) {
					if (!method.isImplementing) line("$decl;")
				} else {
					val meta = method.annotationsList.getTyped<HaxeMeta>()?.value
					if (meta != null) line(meta)
					val rbody = if (method.body != null) {
						method.body
					} else if (method.bodyRef != null) {
						program[method.bodyRef!!]?.body
					} else {
						null
					}
					line(decl) {
						try {
							// @TODO: Do not hardcode this!
							if (method.name == "throwParameterIsNullException") line("HaxeNatives.debugger();")
							val javaBody = if (rbody != null) {
								rbody.genBodyWithFeatures()
							} else Indenter.gen {
								line("throw R.n(HAXE_CLASS_NAME, ${method.id});")
							}
							line(method.getHaxeNativeBody(javaBody).toString().template())
							if (method.methodVoidReturnThis) line("return this;")
						} catch (e: Throwable) {
							//e.printStackTrace()
							log.warn("WARNING haxe_gen.writeMethod:" + e.message)

							line("HaxeNatives.debugger(); throw " + "Errored method: ${clazz.name}.${method.name} :: ${method.desc} :: ${e.message}".quote() + ";")
						}
					}
				}
			}
		}

		fun addClassInit(clazz: AstClass) = Indenter.gen {
			line("static public var SII = false;");
			for (index in strings.getIndices()) {
				val str = strings.getStringWithId(index)
				val id = strings.getId(str)
				line("static private var $id:$JAVA_LANG_STRING;")
			}

			line("static public function SI()") {
				line("if (SII) return;")
				line("SII = true;")

				for (index in strings.getIndices()) {
					val str = strings.getStringWithId(index)
					val id = strings.getId(str)
					line("$id = ${names.escapeConstant(str)};")
				}

				if (clazz.hasStaticInit) {
					val methodName = clazz.staticInitMethod!!.haxeName
					line("$methodName();")
				}
			}
		}


		//val annotationTypeHaxeName = AstMethodRef(java.lang.annotation.Annotation::class.java.name.fqname, "annotationType", AstType.build { METHOD(java.lang.annotation.Annotation::class.java.ast()) }).haxeName
		val annotationTypeHaxeName = AstMethodRef(java.lang.annotation.Annotation::class.java.name.fqname, "annotationType", types.build { METHOD(CLASS) }).haxeName
		// java.lang.annotation.Annotation
		//abstract fun annotationType():Class<out Annotation>

		val classCodeIndenter = Indenter.gen {
			line("package ${clazz.name.haxeGeneratedFqPackage};")

			if (isAbstract) line("// ABSTRACT")
			var declaration = "$classType $simpleClassName"
			if (isInterface) {
				if (clazz.implementing.isNotEmpty()) declaration += getInterfaceList("extends")
			} else {
				if (clazz.extending != null && clazz.name.fqname != "java.lang.Object") declaration += " extends ${clazz.extending!!.haxeClassFqName}"
				if (clazz.implementing.isNotEmpty()) declaration += getInterfaceList("implements")
			}

			// Additional imports!
			val imports = clazz.annotationsList.getTyped<HaxeImports>()?.value
			if (imports != null) for (i in imports) line(i)

			val meta = clazz.annotationsList.getTyped<HaxeMeta>()?.value
			val availableOnTargets = clazz.annotationsList.getTyped<HaxeAvailableOnTargets>()?.value

			if (availableOnTargets != null) {
				//println("availableOnTargets:" + availableOnTargets.joinToString(" || "))
				line("#if (" + availableOnTargets.joinToString(" || ") + ")")
			}
			if (meta != null) line(meta)
			line(declaration) {
				if (!isInterface) {
					line("public function new()") {
						line(if (isRootObject) "" else "super();")
						line("SI();")
					}
				}

				val nativeMembers = clazz.annotationsList.getTyped<HaxeAddMembers>()?.value?.toList() ?: listOf()

				for (member in nativeMembers) line(member.template())

				if (!isInterface) {
					for (field in clazz.fields) {
						line(writeField(field, isInterface))
					}
				}

				for (method in clazz.methods) {
					if (isInterface && method.isStatic) continue
					line(writeMethod(method, isInterface))
				}

				// @TODO: Check!
				//if (!isInterface) {
				//	//println(clazz.fqname + " -> " + program.getAllInterfaces(clazz))
				//	val isFunctionType = program.isImplementing(clazz, JTranscFunction::class.java.name)
				//
				//	if (isFunctionType) {
				//		val executeFirst = clazz.methodsByName["execute"]!!.first()
				//		line("public const _execute:Function = ${executeFirst.ref.haxeName};")
				//	}
				//}

				/*
				if (isNormalClass) {
					val override = if (isRootObject) " " else "override "
					line("$override public function toString():String { return HaxeNatives.toNativeString(this.toString__Ljava_lang_String_()); }")
				}
				*/
				if (isRootObject) {
					line("public function toString():String { return HaxeNatives.toNativeString(this.$toStringHaxeName()); }")
					line("public function hashCode():Int { return this.$hashCodeHaxeName(); }")
				}

				if (!isInterface) {
					line(addClassInit(clazz))
					line(dumpClassInfo(clazz))
				}
			}

			if (availableOnTargets != null) {
				line("#end")
			}

			//if (isInterfaceWithStaticMembers) {
			if (isInterface) {
				val javaLangObjectClass = program[FqName("java.lang.Object")]

				line("class ${simpleClassName}_IFields") {
					line("public function new() {}")
					for (field in clazz.fields) line(writeField(field, isInterface = false))
					for (method in clazz.methods.filter { it.isStatic }) line(writeMethod(method, isInterface = false))
					line(addClassInit(clazz))
					line(dumpClassInfo(clazz))
				}


				if (clazz in allAnnotationTypes) {
					line("// annotation type: ${clazz.name}")

					line("class ${names.getAnnotationProxyName(clazz.astType)} extends ${names.haxeName<JTranscAnnotationBase>()} implements ${clazz.name.haxeClassFqName}") {
						line("private var _data:Array<Dynamic>;")
						line("public function new(_data:Dynamic = null) { super(); this._data = _data; }")

						line("public function $annotationTypeHaxeName():$JAVA_LANG_CLASS { return HaxeNatives.resolveClass(${clazz.fqname.quote()}); }")
						line("override public function $getClassHaxeName():$JAVA_LANG_CLASS { return HaxeNatives.resolveClass(${clazz.fqname.quote()}); }")
						for ((index, m) in clazz.methods.withIndex()) {
							line("public function ${m.haxeName}():${m.methodType.ret.haxeTypeTag} { return this._data[$index]; }")
						}
					}
				}

				if (clazz.hasFFI) {
					line("class ${simpleClassName}_FFI extends $JAVA_LANG_OBJECT implements $simpleClassName implements HaxeFfiLibrary") {
						val methods = clazz.allMethodsToImplement.map { clazz.getMethodInAncestorsAndInterfaces(it)!! }
						line("private var __ffi_lib:haxe.Int64 = 0;")
						for (method in methods) {
							line("private var __ffi_${method.name}:haxe.Int64 = 0;")
						}
						line("@:noStack public function _ffi__load(library:String)") {
							//line("trace('Loading... \$library');")
							line("#if cpp")
							line("__ffi_lib = HaxeDynamicLoad.dlopen(library);")
							line("if (__ffi_lib == 0) trace('Cannot open library: \$library');")
							for (method in methods) {
								line("__ffi_${method.name} = HaxeDynamicLoad.dlsym(__ffi_lib, '${method.name}');")
								line("if (__ffi_${method.name} == 0) trace('Cannot load method ${method.name}');")
							}
							line("#end")
						}
						line("@:noStack public function _ffi__close()") {
							line("#if cpp")
							line("HaxeDynamicLoad.dlclose(__ffi_lib);")
							line("#end")
						}

						fun AstType.castToHaxe(): String {
							return when (this) {
								AstType.VOID -> ""
								AstType.BOOL -> "(bool)"
								AstType.INT -> "(int)"
								AstType.LONG -> "(int)" // @TODO!
							//AstType.STRING -> "char*"
								else -> "(void*)"
							}
						}

						fun AstType.nativeType(): String {
							return when (this) {
								AstType.VOID -> "void"
								AstType.BOOL -> "bool"
								AstType.INT -> "int"
								AstType.LONG -> "int" // @TODO!
								AstType.STRING -> "char*"
								else -> "void*"
							}
						}

						fun AstType.castToNative(): String {
							return "(${this.nativeType()})"
						}

						fun AstType.castToNativeHx(str: String): String {
							return when (this) {
								AstType.STRING -> "cpp.NativeString.c_str(($str)._str)"
								else -> return str
							}
						}

						fun AstType.METHOD.toCast(stdCall: Boolean): String {
							val argTypes = this.args.map { it.type.nativeType() }
							val typeInfix = if (stdCall) "__stdcall " else " "
							return "(${this.ret.nativeType()} (${typeInfix}*)(${argTypes.joinToString(", ")}))(void *)(size_t)"
						}

						for (method in methods) {
							val methodName = method.ref.haxeName
							val methodType = method.methodType
							val margs = methodType.args.map { it.name + ":" + it.type.haxeTypeTag }.joinToString(", ")
							val rettype = methodType.ret.haxeTypeTag

							val stdCall = method.annotationsList.contains<StdCall>()

							line("@:noStack public function $methodName($margs):$rettype") {
								val argIds = methodType.args.withIndex().map { "${it.value.type.castToNative()}{${(it.index + 1)}}" }.joinToString(", ")
								val cppArgs = (listOf("__ffi_${method.name}") + methodType.args.map { it.type.castToNativeHx(it.name) }).joinToString(", ")
								val mustReturn = methodType.ret != AstType.VOID
								val retstr = if (mustReturn) "return " else ""
								line("#if cpp untyped __cpp__('$retstr ${methodType.ret.castToHaxe()}((${methodType.toCast(stdCall)}{0})($argIds));', $cppArgs); #end")
								if (mustReturn) line("return cast 0;")
							}
						}
					}
				}

				line("class ${simpleClassName}_Proxy extends $JAVA_LANG_OBJECT implements $simpleClassName") {

					line("private var __clazz:$JAVA_LANG_CLASS;")
					line("private var __invocationHandler:$invocationHandlerHaxeName;")
					line("private var __methods:Map<Int, $methodHaxeName>;")
					line("public function new(handler:$invocationHandlerHaxeName)") {
						line("super();")
						line("this.__clazz = HaxeNatives.resolveClass(\"${clazz.name.fqname}\");")
						line("this.__invocationHandler = handler;")
					}
					// public Object invoke(Object proxy, Method method, Object[] args)
					line("private function _invoke(methodId:Int, args:Array<$JAVA_LANG_OBJECT>):$JAVA_LANG_OBJECT") {
						line("var method = this.__clazz.locateMethodById(methodId);");
						line("return this.__invocationHandler.$invokeHaxeName(this, method, ${names.HaxeArrayAny}.fromArray(args, '[Ljava.lang.Object;'));")
					}

					for (methodRef in clazz.allMethodsToImplement) {
						val mainMethod = clazz.getMethodInAncestorsAndInterfaces(methodRef)
						if (mainMethod == null) {
							println("NULL methodRef: $methodRef")
							continue
						}
						val mainMethodName = mainMethod.ref.haxeName
						val methodType = mainMethod.methodType
						val margs = methodType.args.map { it.name + ":" + it.type.haxeTypeTag }.joinToString(", ")
						val rettype = methodType.ret.haxeTypeTag
						val returnOrEmpty = if (methodType.retVoid) "" else "return "
						val margBoxedNames = methodType.args.map { it.type.box(it.name) }.joinToString(", ")
						val typeStr = methodType.functionalType
						val methodInObject = javaLangObjectClass[mainMethod.ref.withoutClass]
						val methodId = mainMethod.id

						line("${methodInObject.nullMap("override", "")} public function $mainMethodName($margs):$rettype { return " + methodType.ret.unbox("this._invoke($methodId, [$margBoxedNames]") + ");  }")
					}
				}

				val methodsWithoutBody = clazz.methods.filter { it.body == null }
				if (methodsWithoutBody.size == 1 && clazz.implementing.size == 0) {
					// @TODO: Probably it should allow interfaces extending!
					val mainMethod = methodsWithoutBody.first()
					val mainMethodName = mainMethod.ref.haxeName
					val methodType = mainMethod.methodType
					val margs = methodType.args.map { it.name + ":" + it.type.haxeTypeTag }.joinToString(", ")
					val rettype = methodType.ret.haxeTypeTag
					val returnOrEmpty = if (methodType.retVoid) "" else "return "
					val margNames = methodType.args.map { it.name }.joinToString(", ")
					val typeStr = methodType.functionalType
					line("class ${simpleClassName}_Lambda extends $JAVA_LANG_OBJECT implements $simpleClassName") {
						line("private var ___func__:$typeStr;")
						line("public function new(func: $typeStr) { super(); this.___func__ = func; }")
						val methodInObject = javaLangObjectClass[mainMethod.ref.withoutClass]
						line("${methodInObject.nullMap("override", "")} public function $mainMethodName($margs):$rettype { $returnOrEmpty ___func__($margNames); }")
						for (dmethod in clazz.methods.filter { it.body != null }) {
							val dmethodName = dmethod.ref.haxeName
							val dmethodArgs = dmethod.methodType.args.map { it.name + ":" + it.type.haxeTypeTag }.joinToString(", ")
							val dmethodRettype = dmethod.methodType.ret.haxeTypeTag
							line("${methodInObject.nullMap("override", "")} public function $dmethodName($dmethodArgs):$dmethodRettype") {
								line(dmethod.body!!.genBodyWithFeatures())
							}
						}
					}
				}
			}
		}

		val lineMappings = hashMapOf<Int, Int>()

		val fileStr = classCodeIndenter.toString { sb, line, data ->
			if (data is AstStm.LINE) {
				//println("MARKER: ${sb.length}, $line, $data, ${clazz.source}")
				lineMappings[line] = data.line
				//clazzName.internalFqname + ".java"
			}
		}

		val haxeFilePath = clazz.name.haxeFilePath
		vfs["$haxeFilePath"] = fileStr
		vfs["$haxeFilePath.map"] = Sourcemaps.encodeFile(vfs["$haxeFilePath"].realpathOS, fileStr, clazz.source, lineMappings)
	}

	//val FqName.as3Fqname: String get() = this.fqname
	//fun AstMethod.getHaxeMethodName(program: AstProgram): String = this.ref.getHaxeMethodName(program)

	val AstVisibility.haxe: String get() = "public"

	val AstType.haxeTypeTag: FqName get() = names.getNativeType(this, TypeKind.TYPETAG)
	val AstType.haxeTypeNew: FqName get() = names.getNativeType(this, TypeKind.NEW)
	val AstType.haxeTypeCast: FqName get() = names.getNativeType(this, TypeKind.CAST)
	val AstType.haxeDefault: Any? get() = names.getHaxeDefault(this)
	val AstType.haxeDefaultString: String get() = names.escapeConstant(names.getHaxeDefault(this), this)
	val AstType.METHOD.functionalType: String get() = names.getHaxeFunctionalType(this)

	fun AstType.box(arg: String): String {
		return when (this) {
			is AstType.Primitive -> "HaxeNatives.box${this.shortName.capitalize()}($arg)"
			else -> "cast($arg)";
		}
	}

	fun AstType.unbox(arg: String): String {
		return when (this) {
			is AstType.Primitive -> "HaxeNatives.unbox${this.shortName.capitalize()}($arg)"
			else -> "cast($arg)";
		}
	}

	val AstField.haxeName: String get() = names.getHaxeFieldName(this)
	val AstFieldRef.haxeName: String get() = names.getHaxeFieldName(this)
	val AstFieldRef.haxeStaticText: String get() = names.getStaticFieldText(this)

	val AstMethod.haxeName: String get() = names.getHaxeMethodName(this)
	val AstMethodRef.haxeName: String get() = names.getHaxeMethodName(this)

	val AstMethod.haxeIsOverriding: Boolean get() = this.isOverriding && !this.isInstanceInit

	val FqName.haxeLambdaName: String get() = names.getHaxeClassFqNameLambda(this)
	val FqName.haxeClassFqName: String get() = names.getHaxeClassFqName(this)
	val FqName.haxeClassFqNameInt: String get() = names.getHaxeClassFqNameInt(this)
	val FqName.haxeFilePath: String get() = names.getHaxeFilePath(this)
	val FqName.haxeGeneratedFqPackage: String get() = names.getHaxeGeneratedFqPackage(this)
	val FqName.haxeGeneratedFqName: FqName get() = names.getHaxeGeneratedFqName(this)
	val FqName.haxeGeneratedSimpleClassName: String get() = names.getHaxeGeneratedSimpleClassName(this)
	val AstArgument.haxeNameAndType: String get() = this.name + ":" + this.type.haxeTypeTag

}