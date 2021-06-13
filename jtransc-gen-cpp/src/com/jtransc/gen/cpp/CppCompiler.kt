package com.jtransc.gen.cpp

import com.jtransc.JTranscSystem
import com.jtransc.JTranscVersion
import com.jtransc.env.*
import com.jtransc.error.invalidOp
import com.jtransc.gen.common.BaseCompiler
import com.jtransc.vfs.ExecOptions
import com.jtransc.vfs.LocalVfs
import com.jtransc.vfs.get
import java.io.File

object CppCompiler {
	val CPP_COMMON_FOLDER by lazy {
		val jtranscVersion = JTranscVersion.getVersion().replace('.', '_');
		val folder = File(System.getProperty("user.home") + "/.jtransc/cpp/" + jtranscVersion)
		folder.mkdirs()
		LocalVfs(folder)
	}

	fun genCommand(
		programFile: File, debug: Boolean = false,
		libs: List<String> = listOf(),
		includeFolders: List<String> = listOf(),
		libsFolders: List<String> = listOf(),
		defines: List<String> = listOf(),
		extraVars: Map<String, List<String>>
	): List<String> {
		// -O0 = 23s && 7.2MB
		// -O4 = 103s && 4.3MB
		val compiler = listOf(GPP, CLANG, CMAKE).firstOrNull { it.available } ?: invalidOp("Can't find CPP compiler (cmake, g++ or clang), please install one of them and put in the path.")
		return compiler.genCommand(programFile, BaseCompiler.Config(debug, libs, includeFolders, libsFolders, defines, extraVars))
	}

	// g++ -O4 -fno-stack-protector -fexceptions -frtti -std=c++11 -pthread -g program.cpp
	fun addCommonCmdArgs(cmdAndArgs: MutableList<String>, config: BaseCompiler.Config) {
		for (define in config.defines) cmdAndArgs += "-D$define"
		for (includeFolder in config.includeFolders) cmdAndArgs += "-I$includeFolder"
		for (libFolder in config.libsFolders) cmdAndArgs += "-L$libFolder"
		for (lib in config.libs) cmdAndArgs += "-l$lib"
		//if (!JTranscSystem.isMac()) cmdAndArgs += "-lrt"
		cmdAndArgs += "-fexceptions"
		cmdAndArgs += "-frtti"
		cmdAndArgs += "-D_FORTIFY_SOURCE=0" // Without this: *** buffer overflow detected ***: terminated
		if (!OS.isMac) {
			cmdAndArgs += "-static"
			cmdAndArgs += "-static-libgcc"
			cmdAndArgs += "-static-libstdc++"
			cmdAndArgs += "-fdce"
			cmdAndArgs += "-Wl,--gc-sections"
		}
		//cmdAndArgs += "-Wa,-mbig-obj"
		//cmdAndArgs += "-flto"
		//cmdAndArgs += "-ffunction-sections"
		//cmdAndArgs += "-fdata-sections"
		cmdAndArgs += "-fwhole-program"
		cmdAndArgs += "-std=c++11"
		if (config.debug) {
			cmdAndArgs += "-g"
			cmdAndArgs += "-O0"
		} else {
			//cmdAndArgs += "-s"
			cmdAndArgs += "-fomit-frame-pointer"
			cmdAndArgs += "-fno-stack-protector"
			cmdAndArgs += "-Ofast"
		}
		if (!JTranscSystem.isMac()) {
			cmdAndArgs += "-pthread"
		}
	}

	object CMAKE : BaseCompiler("cmake") {
		override fun genCommand(programFile: File, config: Config): List<String> {
			val cmake = cmd!!
			val cmakeCache = programFile.parentFile["CMakeCache.txt"]
			if (!cmakeCache.exists()) {
				LocalVfs(cmakeCache.parentFile).exec(listOf(cmake, "."), ExecOptions(sysexec = true, fixencoding = false, passthru = true))
			}
			val cfg = if (config.debug) "Debug" else "Release"
			//return listOf(cmake, "--build", ".", "--target", "ALL_BUILD", "-DCMAKE_GENERATOR_PLATFORM=x64", "--config", config)
			//return listOf(cmake) + listOf("--build", ".", "--target", "ALL_BUILD", "--config", cfg)
			val args = config.extraVars["CMAKE_ARGS"] ?: listOf()
			return listOf(cmake) + listOf("--build", ".", "--config", cfg) + args
		}
	}

	// "c:\Users\soywi\.konan\dependencies\msys2-mingw-w64-x86_64-clang-llvm-lld-compiler_rt-8.0.1\bin\clang++.exe" -std=c++11 -Wno-parentheses-equality -static -O3 program.cpp -o program.exe -Wno-unused-value
	object CLANG : BaseCompiler("clang++") {
		override fun genCommand(programFile: File, config: Config): List<String> {
			val cmdAndArgs = arrayListOf<String>()
			cmdAndArgs += cmd!!
			if (JTranscSystem.isWindows()) cmdAndArgs += "-fms-compatibility-version=19.00"
			cmdAndArgs += "-Wno-writable-strings"
			cmdAndArgs += "-Wno-unused-value"
			cmdAndArgs += "-Wno-parentheses-equality"
			cmdAndArgs += "-Wimplicitly-unsigned-literal"
			cmdAndArgs += programFile.absolutePath
			addCommonCmdArgs(cmdAndArgs, config)
			return cmdAndArgs
		}
	}

	object GPP : BaseCompiler("g++") {
		override fun genCommand(programFile: File, config: Config): List<String> {
			val cmdAndArgs = arrayListOf<String>()
			cmdAndArgs += cmd!!
			cmdAndArgs += "-w"
			cmdAndArgs += programFile.absolutePath
			addCommonCmdArgs(cmdAndArgs, config)
			return cmdAndArgs
		}
	}
}
