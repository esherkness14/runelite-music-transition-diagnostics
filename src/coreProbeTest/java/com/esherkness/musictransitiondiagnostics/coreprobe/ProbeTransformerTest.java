package com.esherkness.musictransitiondiagnostics.coreprobe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static org.junit.Assert.*;

/** Synthetic targets only: these tests never invoke real music engine methods. */
public class ProbeTransformerTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	@After public void closeLog() { CoreProbeLog.close(); }

	@Test
	public void allEntriesLogOnceAndPreserveEveryArgument() throws Exception
	{
		Path log = temporary.newFile("probe.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm();
		for (String owner : ProbeTransformer.TARGETS.keySet())
		{
			if (owner.equals("nu")) { continue; } // Instance setter is tested below.
			Class<?> target = define(owner, ProbeTransformer.instrument(owner, fixture(owner, false)));
			Method method = target.getDeclaredMethods()[0];
			ArrayList<Integer> ids = new ArrayList<>(Arrays.asList(123, -1, 456));
			Object[] arguments = arguments(owner, ids);
			method.invoke(null, arguments);
			assertEquals(1, target.getField("executions").getInt(null));
			for (int i = 0; i < arguments.length; i++)
			{
				assertEquals(arguments[i], target.getField("seen" + i).get(null));
			}
			if (owner.equals("rj") || owner.equals("ij"))
			{
				assertSame(ids, target.getField("seen0").get(null));
			}
			assertEquals(Arrays.asList(123, -1, 456), ids);
		}
		String text = Files.readString(log);
		assertEquals(4, text.lines().filter(l -> l.contains(" method=")).count());
		assertTrue(text.contains("requestedIds=[123, -1, 456] outgoingDelay=11 outgoingFade=22 incomingDelay=33 incomingFade=44"));
		assertTrue(text.contains("requestCount=3 outgoingDelay=11 outgoingFade=22 incomingDelay=33 incomingFade=44 specialRoute=true"));
		assertTrue(text.contains("outgoingDelay=11 outgoingFade=22 arg3=7"));
		assertTrue(text.contains("arg1=11 arg2=22 arg3=33 arg4=44 arg5=55"));
		assertEquals(4, text.lines().filter(l -> l.contains(" method="))
			.filter(l -> l.matches(".*timestamp=\\d{4}-.*\\.\\d{3}Z nanoTime=-?\\d+ .*caller=.* callerCategory=OTHER")).count());
	}

	@Test
	public void originalExceptionStillPropagates() throws Exception
	{
		Class<?> target = define("bk", ProbeTransformer.instrument("bk", fixture("bk", true)));
		try
		{
			target.getDeclaredMethods()[0].invoke(null, 11, 22, (byte) 7);
			fail("Original exception was swallowed");
		}
		catch (InvocationTargetException expected)
		{
			assertTrue(expected.getCause() instanceof IllegalArgumentException);
			assertEquals("fixture-original", expected.getCause().getMessage());
		}
		assertEquals(1, target.getField("executions").getInt(null));
	}

	@Test
	public void loggingFailureDoesNotPreventOriginalBody() throws Exception
	{
		Path log = temporary.newFile("failure.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm();
		ArrayList<Integer> brokenObservation = new ArrayList<Integer>()
		{
			@Override public Object[] toArray() { throw new AssertionError("test-only observation failure"); }
		};
		Class<?> target = define("rj", ProbeTransformer.instrument("rj", fixture("rj", false)));
		target.getDeclaredMethods()[0].invoke(null, arguments("rj", brokenObservation));
		assertEquals(1, target.getField("executions").getInt(null));
		assertSame(brokenObservation, target.getField("seen0").get(null));
		assertTrue(Files.readString(log).contains("state=DISABLED reason=event-observation-failed"));
	}

	@Test
	public void normalRunRemainsObservationOnlyForAreaSignature() throws Exception
	{
		CoreProbeLog.initialize(temporary.newFile("observe-only.log").toPath());
		CoreProbeLog.arm(null);
		Class<?> target = define("ij", ProbeTransformer.instrument("ij", fixture("ij", false), false));
		invokeIj(target, 0, 60, 60, 0, false);
		assertEquals(0, target.getField("seen1").getInt(null));
		assertEquals(60, target.getField("seen2").getInt(null));
		assertEquals(60, target.getField("seen3").getInt(null));
		assertEquals(0, target.getField("seen4").getInt(null));
		assertEquals(1, target.getField("executions").getInt(null));
	}

	@Test
	public void defaultAndCustomCrossfadeTimingsPreserveOutgoingValuesAndRunOriginal() throws Exception
	{
		for (int[] settings : new int[][]{{120, 0}, {90, 30}, {60, 60}})
		{
			int fade = settings[0];
			int delay = settings[1];
			Path log = temporary.newFile("override-" + fade + "-" + delay + ".log").toPath();
			CoreProbeLog.initialize(log);
			CoreProbeLog.arm(settings(fade, delay, false));
			Class<?> target = define("ij", ProbeTransformer.instrument("ij", fixture("ij", false), true));
			ArrayList<Integer> requests = invokeIj(target, 0, 60, 60, 0, false);
			assertSame(requests, target.getField("seen0").get(null));
			assertEquals(0, target.getField("seen1").getInt(null));
			assertEquals(60, target.getField("seen2").getInt(null));
			assertEquals(delay, target.getField("seen3").getInt(null));
			assertEquals(fade, target.getField("seen4").getInt(null));
			assertFalse(target.getField("seen5").getBoolean(null));
			assertEquals(77, target.getField("seen6").getInt(null));
			assertEquals(1, target.getField("executions").getInt(null));
			String text = Files.readString(log);
			assertTrue(text.contains("state=ARMED version=1.12.39 targets=5 mode=AREA_INCOMING_FADE_TEST configuredIncomingFade=" + fade
				+ " configuredIncomingDelay=" + delay + " probeStreamVolume=false"));
			assertTrue(text.contains("originalTimings=[0,60,60,0]"));
			assertTrue(text.contains("effectiveTimings=[0,60," + delay + "," + fade + "]"));
		}
	}

	@Test
	public void naturalSignaturesRemainUntouchedInAreaFadeMode() throws Exception
	{
		CoreProbeLog.initialize(temporary.newFile("natural.log").toPath());
		CoreProbeLog.arm(settings(120, 0, false));
		assertIjIncomingFade(0, 20, 0, 0, false, 0);
		assertIjIncomingFade(0, 0, 0, 0, false, 0);
	}

	@Test
	public void specialRouteAndUnrelatedTimingsRemainUntouched() throws Exception
	{
		CoreProbeLog.initialize(temporary.newFile("nonmatches.log").toPath());
		CoreProbeLog.arm(settings(120, 0, false));
		assertIjIncomingFade(0, 60, 60, 0, true, 0);
		assertIjIncomingFade(1, 60, 60, 0, false, 0);
		assertIjIncomingFade(0, 59, 60, 0, false, 0);
		assertIjIncomingFade(0, 60, 59, 0, false, 0);
		assertIjIncomingFade(0, 60, 60, 1, false, 1);
	}

	@Test
	public void agentModeValidatesFadeDelayAndOptionalVolumeProbe()
	{
		assertNull(CoreProbeAgent.parseSettings(null));
		assertNull(CoreProbeAgent.parseSettings(""));
		for (int[] values : new int[][]{{60, 0}, {90, 30}, {120, 60}, {300, 0}})
		{
			CoreProbeAgent.AreaFadeSettings parsed = CoreProbeAgent.parseSettings(
				"area-incoming-fade-test:" + values[0] + ":" + values[1] + ":true");
			assertEquals(values[0], parsed.incomingFade);
			assertEquals(values[1], parsed.incomingDelay);
			assertTrue(parsed.probeStreamVolume);
		}
		for (String invalid : new String[]{"area-incoming-fade-test", "area-incoming-fade-test:",
			"area-incoming-fade-test:-1:0:false", "area-incoming-fade-test:0:0:false",
			"area-incoming-fade-test:301:0:false", "area-incoming-fade-test:999999999999:0:false",
			"area-incoming-fade-test:90.5:0:false", "area-incoming-fade-test: 90:0:false",
			"area-incoming-fade-test:090:0:false", "area-incoming-fade-test:120:-1:false",
			"area-incoming-fade-test:120:61:false", "area-incoming-fade-test:120:abc:false",
			"area-incoming-fade-test:120:01:false", "area-incoming-fade-test:120:0:True",
			"area-incoming-fade-test:120:0:1", "area-incoming-fade-test:120:0",
			"unknown:120:0:false"})
		{
			assertThrows(invalid, IllegalArgumentException.class, () -> CoreProbeAgent.parseSettings(invalid));
		}
	}

	@Test
	public void streamVolumeProbeIsDisabledByDefaultEvenAfterEligibleOverride() throws Exception
	{
		Path log = temporary.newFile("unprobed-listening.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm(settings(120, 0, false));
		byte[] original = fixture("nu", false);
		assertArrayEquals(original, ProbeTransformer.instrument("nu", original, true, false));
		assertEquals(120L, CoreProbeLog.effectiveIncomingTimings(0, 60, 60, 0, false));
		CoreProbeLog.streamVolumeWriteAt(new Object(), 80, System.nanoTime());
		String text = Files.readString(log);
		assertTrue(text.contains("effectiveTimings=[0,60,0,120]"));
		assertFalse(text.contains("type=STREAM_VOLUME_WRITE"));
		assertFalse(text.contains("volumeWindow="));
	}

	@Test
	public void nativeVolumeSetterIsLoggedOnlyInsideAcceptedWindow() throws Exception
	{
		Path log = temporary.newFile("volume-window.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm(settings(120, 0, true));
		byte[] original = fixture("nu", false);
		assertArrayEquals(original, ProbeTransformer.instrument("nu", original, true, false));
		Class<?> target = define("nu", ProbeTransformer.instrument("nu", original, true, true));
		Object stream = target.getConstructor().newInstance();
		Method setter = target.getDeclaredMethod("az", int.class, int.class);

		setter.invoke(stream, 40, 7); // No accepted area override yet.
		assertEquals(0, Files.readString(log).lines().filter(l -> l.contains("type=STREAM_VOLUME_WRITE")).count());
		assertEquals(120L, CoreProbeLog.effectiveIncomingTimings(0, 60, 60, 0, false));
		for (int volume : new int[]{0, 1, 20, 120})
		{
			setter.invoke(stream, volume, 7);
		}
		assertEquals(5, target.getField("executions").getInt(null));
		assertEquals(120, target.getField("seen0").getInt(null));
		assertEquals(7, target.getField("seen1").getInt(null));
		String text = Files.readString(log);
		assertEquals(4, text.lines().filter(l -> l.contains("type=STREAM_VOLUME_WRITE")).count());
		assertTrue(text.contains("requestedVolume=0"));
		assertTrue(text.contains("requestedVolume=120"));
		assertTrue(text.contains("streamIdentity=0x" + Integer.toHexString(System.identityHashCode(stream))));
		assertTrue(text.contains("volumeWindow=1"));
		assertTrue(text.contains(" caller="));
		CoreProbeLog.streamVolumeWriteAt(stream, 200,
			System.nanoTime() + CoreProbeLog.VOLUME_WINDOW_NANOS + 1);
		assertEquals(4, Files.readString(log).lines().filter(l -> l.contains("type=STREAM_VOLUME_WRITE")).count());
	}

	@Test
	public void nonmatchingRequestNeverOpensVolumeWindow() throws Exception
	{
		Path log = temporary.newFile("no-volume-window.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm(settings(120, 0, true));
		assertEquals(0L, CoreProbeLog.effectiveIncomingTimings(0, 20, 0, 0, false));
		Class<?> target = define("nu", ProbeTransformer.instrument("nu", fixture("nu", false), true, true));
		target.getDeclaredMethod("az", int.class, int.class)
			.invoke(target.getConstructor().newInstance(), 80, 7);
		assertFalse(Files.readString(log).contains("type=STREAM_VOLUME_WRITE"));
	}

	@Test
	public void volumeWindowHasAHardLogLimit() throws Exception
	{
		Path log = temporary.newFile("volume-limit.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm(settings(120, 0, true));
		CoreProbeLog.effectiveIncomingTimings(0, 60, 60, 0, false);
		Object stream = new Object();
		long start = System.nanoTime();
		for (int i = 0; i < 520; i++)
		{
			CoreProbeLog.streamVolumeWriteAt(stream, i, start + i);
		}
		String text = Files.readString(log);
		assertEquals(512, text.lines().filter(l -> l.contains("type=STREAM_VOLUME_WRITE")).count());
		assertEquals(1, text.lines().filter(l -> l.contains("type=STREAM_VOLUME_LIMIT")).count());
	}

	@Test
	public void nativeWriterNamesAreClassifiedConservatively()
	{
		assertEquals("FadeInTask", CoreProbeLog.musicTask("wp.az"));
		assertEquals("FadeOutTask", CoreProbeLog.musicTask("wo.ab"));
		assertEquals("StartSongTask", CoreProbeLog.musicTask("wk.az"));
		assertEquals("OTHER", CoreProbeLog.musicTask("wpFake.az"));
	}

	@Test
	public void rejectWrongSignatureAndUnexpectedDefinition()
	{
		assertThrows(IllegalArgumentException.class, () -> ProbeTransformer.instrument("ij", fixture("rj", false)));
		Map<String, byte[]> original = new LinkedHashMap<>();
		ProbeTransformer.TARGETS.keySet().forEach(n -> original.put(n, fixture(n, false)));
		ProbeTransformer transformer = new ProbeTransformer(Path.of("not-the-client.jar"), getClass().getClassLoader(), original, false, false);
		assertNull(transformer.transform(getClass().getClassLoader(), "rj", null, null, original.get("rj")));
		assertNull(transformer.transform(getClass().getClassLoader(), "unrelated", null, null, new byte[0]));
		byte[] wrongSignature = fixture("rj", false);
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(wrongSignature).accept(node, 0);
		node.methods.get(0).name = "notBc";
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		assertThrows(IllegalArgumentException.class, () -> ProbeTransformer.instrument("rj", writer.toByteArray()));
	}

	@Test
	public void conservativeCallerClassification()
	{
		assertEquals("PACKET", CoreProbeLog.category("client.ia"));
		assertEquals("PACKET", CoreProbeLog.category("client.mh"));
		assertEquals("SCRIPT", CoreProbeLog.category("ee.bt"));
		assertEquals("OTHER", CoreProbeLog.category("client.aet"));
		assertEquals("OTHER", CoreProbeLog.category("something.ee.bt"));
	}

	@Test
	public void eachRunTruncatesItsDedicatedLog() throws Exception
	{
		Path log = temporary.newFile("fresh.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.status("OLD-RUN", "");
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm();
		assertFalse(Files.readString(log).contains("OLD-RUN"));
		assertTrue(Files.readString(log).contains("ARMED"));
	}

	@Test
	public void actualCallerFramesAreClassifiedWithoutDumpingStacks() throws Exception
	{
		Path log = temporary.newFile("callers.log").toPath();
		CoreProbeLog.initialize(log);
		CoreProbeLog.arm();
		for (String caller : Arrays.asList("client.ia", "client.mh", "ee.bt", "client.aet"))
		{
			String owner = caller.substring(0, caller.indexOf('.'));
			String name = caller.substring(caller.indexOf('.') + 1);
			String descriptor = "(Ljava/util/ArrayList;IIIIB)V";
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
			MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
			body.visitCode();
			body.visitVarInsn(Opcodes.ALOAD, 0);
			for (int i = 1; i <= 5; i++) { body.visitVarInsn(Opcodes.ILOAD, i); }
			body.visitMethodInsn(Opcodes.INVOKESTATIC, "rj", "bc", descriptor, false);
			body.visitInsn(Opcodes.RETURN);
			body.visitMaxs(0, 0);
			body.visitEnd();
			writer.visitEnd();
			Map<String, byte[]> definitions = new LinkedHashMap<>();
			definitions.put(owner, writer.toByteArray());
			definitions.put("rj", ProbeTransformer.instrument("rj", fixture("rj", false)));
			ClassLoader loader = new ClassLoader(getClass().getClassLoader())
			{
				@Override protected Class<?> findClass(String type) throws ClassNotFoundException
				{
					byte[] bytes = definitions.get(type);
					if (bytes == null) { throw new ClassNotFoundException(type); }
					return defineClass(type, bytes, 0, bytes.length);
				}
			};
			loader.loadClass(owner).getDeclaredMethods()[0].invoke(null,
				arguments("rj", new ArrayList<>(Arrays.asList(123))));
			assertTrue(Files.readString(log).contains("caller=" + caller + " callerCategory=" + CoreProbeLog.category(caller)));
		}
		assertEquals(4, Files.readString(log).lines().filter(l -> l.contains(" method=rj.bc ")).count());
	}

	@Test
	public void forgedVersionNameFailsClosedAndMainStillRuns() throws Exception
	{
		Path jar = temporary.newFile("injected-client-1.12.39.jar").toPath();
		try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar)))
		{
			out.putNextEntry(new java.util.jar.JarEntry("rj.class"));
			out.write(fixture("rj", false));
			out.closeEntry();
		}
		Path log = temporary.newFile("rejected.log").toPath();
		Path console = temporary.newFile("console.log").toPath();
		String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process process = new ProcessBuilder(javaExecutable, "-javaagent:" + System.getProperty("probe.agentJar"),
			"-DmusicCoreProbe.log=" + log, "-cp", jar + java.io.File.pathSeparator + System.getProperty("probe.testClasspath"),
			ProbeSmokeMain.class.getName(), "expect-disabled")
			.redirectErrorStream(true).redirectOutput(console.toFile()).start();
		try
		{
			assertTrue("Probe startup hung", process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
			assertEquals(Files.readString(console), 0, process.exitValue());
			assertTrue(Files.readString(log).contains("reason=unsupported-injected-client-version-or-hash"));
		}
		finally
		{
			if (process.isAlive()) { process.destroyForcibly(); }
		}
	}

	private static Object[] arguments(String owner, ArrayList<Integer> ids)
	{
		switch (owner)
		{
			case "rj": return new Object[]{ids, 11, 22, 33, 44, (byte) 7};
			case "ij": return new Object[]{ids, 11, 22, 33, 44, true, 77};
			case "bk": return new Object[]{11, 22, (byte) 7};
			default: return new Object[]{11, 22, 33, 44, 55};
		}
	}

	private static ArrayList<Integer> invokeIj(Class<?> target, int outgoingDelay,
		int outgoingFade, int incomingDelay, int incomingFade, boolean specialRoute) throws Exception
	{
		ArrayList<Integer> requests = new ArrayList<>(Arrays.asList(147));
		target.getDeclaredMethods()[0].invoke(null, requests, outgoingDelay, outgoingFade,
			incomingDelay, incomingFade, specialRoute, 77);
		return requests;
	}

	private void assertIjIncomingFade(int outgoingDelay, int outgoingFade,
		int incomingDelay, int incomingFade, boolean specialRoute, int expected) throws Exception
	{
		Class<?> target = define("ij", ProbeTransformer.instrument("ij", fixture("ij", false), true));
		invokeIj(target, outgoingDelay, outgoingFade, incomingDelay, incomingFade, specialRoute);
		assertEquals(incomingDelay, target.getField("seen3").getInt(null));
		assertEquals(expected, target.getField("seen4").getInt(null));
		assertEquals(1, target.getField("executions").getInt(null));
	}

	private static CoreProbeAgent.AreaFadeSettings settings(int fade, int delay, boolean volumeProbe)
	{
		return new CoreProbeAgent.AreaFadeSettings(fade, delay, volumeProbe);
	}

	private static Class<?> define(String name, byte[] bytes)
	{
		return new ClassLoader(ProbeTransformerTest.class.getClassLoader())
		{
			Class<?> create() { return defineClass(name, bytes, 0, bytes.length); }
		}.create();
	}

	private static byte[] fixture(String owner, boolean throwOriginal)
	{
		String signature = ProbeTransformer.TARGETS.get(owner);
		String name = signature.substring(0, signature.indexOf('('));
		String descriptor = signature.substring(signature.indexOf('('));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		boolean instanceMethod = owner.equals("nu");
		if (instanceMethod)
		{
			MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			constructor.visitCode();
			constructor.visitVarInsn(Opcodes.ALOAD, 0);
			constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			constructor.visitInsn(Opcodes.RETURN);
			constructor.visitMaxs(0, 0);
			constructor.visitEnd();
		}
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "executions", "I", null, null).visitEnd();
		MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC | (instanceMethod ? 0 : Opcodes.ACC_STATIC), name, descriptor, null, null);
		body.visitCode();
		Type[] args = Type.getArgumentTypes(descriptor);
		for (int i = 0; i < args.length; i++)
		{
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "seen" + i, args[i].getDescriptor(), null, null).visitEnd();
			body.visitVarInsn(args[i].getOpcode(Opcodes.ILOAD), i + (instanceMethod ? 1 : 0));
			body.visitFieldInsn(Opcodes.PUTSTATIC, owner, "seen" + i, args[i].getDescriptor());
		}
		body.visitFieldInsn(Opcodes.GETSTATIC, owner, "executions", "I");
		body.visitInsn(Opcodes.ICONST_1);
		body.visitInsn(Opcodes.IADD);
		body.visitFieldInsn(Opcodes.PUTSTATIC, owner, "executions", "I");
		if (throwOriginal)
		{
			body.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException");
			body.visitInsn(Opcodes.DUP);
			body.visitLdcInsn("fixture-original");
			body.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
			body.visitInsn(Opcodes.ATHROW);
		}
		else
		{
			body.visitInsn(Opcodes.RETURN);
		}
		body.visitMaxs(0, 0);
		body.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
