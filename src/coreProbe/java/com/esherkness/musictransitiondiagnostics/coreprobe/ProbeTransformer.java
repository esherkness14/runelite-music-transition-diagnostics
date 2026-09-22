package com.esherkness.musictransitiondiagnostics.coreprobe;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact-version load-time probe; no retransformation and no on-disk jar edits. */
final class ProbeTransformer implements ClassFileTransformer
{
	static final String LOGGER = CoreProbeLog.class.getName().replace('.', '/');
	static final String LOG_DESCRIPTOR = "(Ljava/lang/String;Ljava/util/ArrayList;IIIII)V";
	static final String FADE_DESCRIPTOR = "(IIIIZ)I";
	static final Map<String, String> TARGETS = new LinkedHashMap<>();
	static
	{
		TARGETS.put("rj", "bc(Ljava/util/ArrayList;IIIIB)V");
		TARGETS.put("ij", "af(Ljava/util/ArrayList;IIIIZI)V");
		TARGETS.put("bk", "ab(IIB)V");
		TARGETS.put("im", "as(IIIII)V");
	}

	private final Path jar;
	private final ClassLoader expectedLoader;
	private final Map<String, byte[]> originals;
	private final Map<String, byte[]> instrumented;
	private volatile boolean disabled;

	ProbeTransformer(Path jar, ClassLoader loader, Map<String, byte[]> originals,
		boolean areaFadeTest)
	{
		this.jar = jar;
		this.expectedLoader = loader;
		this.originals = originals;
		this.instrumented = new LinkedHashMap<>();
		// Validate/prepare ALL four methods before registering ANY transformation.
		for (String owner : TARGETS.keySet())
		{
			instrumented.put(owner, instrument(owner, originals.get(owner), areaFadeTest));
		}
	}

	@Override
	public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
		ProtectionDomain domain, byte[] bytes)
	{
		if (!TARGETS.containsKey(name) || disabled)
		{
			return null;
		}
		try
		{
			if (redefined != null || loader != expectedLoader || domain == null
				|| domain.getCodeSource() == null
				|| !jar.equals(Path.of(domain.getCodeSource().getLocation().toURI()).toRealPath())
				|| !Arrays.equals(originals.get(name), bytes))
			{
				throw new IllegalStateException("unexpected class definition");
			}
			CoreProbeLog.status("TRANSFORMED", "method=" + name + "." + TARGETS.get(name));
			return instrumented.get(name).clone();
		}
		catch (Throwable ignored)
		{
			disabled = true;
			CoreProbeLog.disable("class-definition-mismatch-" + name);
			// Fail closed for the probe, but let the original game class load.
			return null;
		}
	}

	static byte[] instrument(String owner, byte[] original)
	{
		return instrument(owner, original, false);
	}

	static byte[] instrument(String owner, byte[] original, boolean areaFadeTest)
	{
		if (original == null || !TARGETS.containsKey(owner))
		{
			throw new IllegalArgumentException("missing target");
		}
		ClassNode node = new ClassNode(Opcodes.ASM9);
		new ClassReader(original).accept(node, 0);
		if (!node.name.equals(owner))
		{
			throw new IllegalArgumentException("wrong class");
		}
		MethodNode target = null;
		for (MethodNode method : node.methods)
		{
			if ((method.name + method.desc).equals(TARGETS.get(owner)))
			{
				if (target != null || (method.access & Opcodes.ACC_STATIC) == 0
					|| (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
				{
					throw new IllegalArgumentException("unsupported method shape");
				}
				target = method;
			}
		}
		if (target == null)
		{
			throw new IllegalArgumentException("missing signature");
		}

		InsnList entry = new InsnList();
		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();
		LabelNode resume = new LabelNode();
		entry.add(start);
		entry.add(new LdcInsnNode(owner + "." + target.name));
		boolean hasList = owner.equals("rj") || owner.equals("ij");
		entry.add(hasList ? new VarInsnNode(Opcodes.ALOAD, 0) : new InsnNode(Opcodes.ACONST_NULL));
		int first = hasList ? 1 : 0;
		int slots = owner.equals("bk") ? 3 : 5;
		for (int i = 0; i < 5; i++)
		{
			entry.add(i < slots ? new VarInsnNode(Opcodes.ILOAD, first + i)
				: new InsnNode(Opcodes.ICONST_0));
		}
		entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOGGER, "enter", LOG_DESCRIPTOR, false));
		if (areaFadeTest && owner.equals("ij"))
		{
			// Only the explicit Experiment 4 variant writes an argument local.
			// The helper returns the original value for every non-match/failure.
			entry.add(new VarInsnNode(Opcodes.ILOAD, 1));
			entry.add(new VarInsnNode(Opcodes.ILOAD, 2));
			entry.add(new VarInsnNode(Opcodes.ILOAD, 3));
			entry.add(new VarInsnNode(Opcodes.ILOAD, 4));
			entry.add(new VarInsnNode(Opcodes.ILOAD, 5));
			entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOGGER,
				"effectiveIncomingFade", FADE_DESCRIPTOR, false));
			entry.add(new VarInsnNode(Opcodes.ISTORE, 4));
		}
		entry.add(end);
		entry.add(new JumpInsnNode(Opcodes.GOTO, resume));
		entry.add(handler);
		entry.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"}));
		entry.add(new InsnNode(Opcodes.POP));
		entry.add(resume);
		entry.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		// Even a linkage failure in diagnostics cannot skip the original method.
		target.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		target.instructions.insert(entry);
		// Entry leaves the original locals/stack unchanged. Preserve existing frames
		// and recompute only stack maxima: never load obfuscated classes to merge types.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
