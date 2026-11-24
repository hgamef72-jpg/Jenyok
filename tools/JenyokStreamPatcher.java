import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class JenyokStreamPatcher {
    private static final String APP_JAR = "app/saturn-pc-remote.jar";
    private static final String HOST_CLASS = "com/saturn/remote/HostApp.class";
    private static final String VIEWER_LISTENER = "com/saturn/remote/ViewerWindow$connect$1.class";
    private static final String PACER_CLASS = "com/saturn/remote/StreamPacer.class";
    private static final String RENDERER_CLASS = "com/saturn/remote/FrameRenderer.class";

    private static AbstractInsnNode nextReal(AbstractInsnNode n) {
        n = n.getNext();
        while (n != null && (n instanceof LabelNode || n instanceof LineNumberNode ||
                n instanceof FrameNode)) n = n.getNext();
        return n;
    }

    private static void replace(AbstractInsnNode first, AbstractInsnNode second, InsnList replacement,
                                 InsnList list) {
        list.insertBefore(first, replacement);
        list.remove(first);
        list.remove(second);
    }

    private static byte[] patchHostClass(byte[] input) {
        ClassNode cn = new ClassNode();
        new ClassReader(input).accept(cn, 0);
        MethodNode method = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("runHostSession") &&
                m.desc.equals("(Lcom/saturn/remote/HostState;Ljava/lang/String;)V")) {
                if (method != null) throw new IllegalStateException("Duplicate runHostSession");
                method = m;
            }
        }
        if (method == null) throw new IllegalStateException("HostApp.runHostSession not found");

        AbstractInsnNode devicesCall = null;
        for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.name.equals("getScreenDevices") &&
                call.desc.equals("()[Ljava/awt/GraphicsDevice;")) {
                devicesCall = call;
                break;
            }
        }
        if (devicesCall == null) throw new IllegalStateException("getScreenDevices call not found");

        // Kotlin's indexed loop uses local 9 as the emitted monitor index,
        // local 10 as the array cursor, and local 11 as the exclusive bound.
        AbstractInsnNode indexConst = null;
        for (AbstractInsnNode n = devicesCall.getNext(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.ISTORE && n instanceof VarInsnNode v && v.var == 9) {
                AbstractInsnNode prev = n.getPrevious();
                while (prev != null && (prev instanceof LabelNode || prev instanceof LineNumberNode ||
                        prev instanceof FrameNode)) prev = prev.getPrevious();
                if (prev != null && prev.getOpcode() == Opcodes.ICONST_0) { indexConst = prev; break; }
            }
        }
        if (indexConst == null) throw new IllegalStateException("Monitor index initialization pattern changed");
        AbstractInsnNode storeIndex = nextReal(indexConst);
        InsnList selectedIndex = new InsnList();
        selectedIndex.add(new VarInsnNode(Opcodes.ALOAD, 1));
        selectedIndex.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "com/saturn/remote/HostState", "getSelectedScreenIndex", "()I", false));
        selectedIndex.add(new VarInsnNode(Opcodes.ISTORE, 9));
        replace(indexConst, storeIndex, selectedIndex, method.instructions);

        AbstractInsnNode loopConst = null;
        for (AbstractInsnNode n = storeIndex.getNext(); n != null; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.ISTORE && n instanceof VarInsnNode v && v.var == 10) {
                AbstractInsnNode prev = n.getPrevious();
                while (prev != null && (prev instanceof LabelNode || prev instanceof LineNumberNode ||
                        prev instanceof FrameNode)) prev = prev.getPrevious();
                if (prev != null && prev.getOpcode() == Opcodes.ICONST_0) { loopConst = prev; break; }
            }
        }
        if (loopConst == null) throw new IllegalStateException("Monitor cursor initialization pattern changed");
        AbstractInsnNode storeCursor = nextReal(loopConst);
        InsnList cursor = new InsnList();
        cursor.add(new VarInsnNode(Opcodes.ILOAD, 9));
        cursor.add(new VarInsnNode(Opcodes.ISTORE, 10));
        replace(loopConst, storeCursor, cursor, method.instructions);

        AbstractInsnNode arrayLoad = null;
        for (AbstractInsnNode n = storeCursor.getNext(); n != null; n = n.getNext()) {
            if (n instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 7) {
                AbstractInsnNode arrayLength = nextReal(n);
                AbstractInsnNode boundStore = nextReal(arrayLength);
                if (arrayLength != null && arrayLength.getOpcode() == Opcodes.ARRAYLENGTH &&
                    boundStore instanceof VarInsnNode b && b.getOpcode() == Opcodes.ISTORE && b.var == 11) {
                    arrayLoad = n;
                    InsnList bound = new InsnList();
                    bound.add(new VarInsnNode(Opcodes.ILOAD, 9));
                    bound.add(new InsnNode(Opcodes.ICONST_1));
                    bound.add(new InsnNode(Opcodes.IADD));
                    bound.add(new VarInsnNode(Opcodes.ISTORE, 11));
                    replace(arrayLoad, boundStore, bound, method.instructions);
                    break;
                }
            }
        }
        if (arrayLoad == null) throw new IllegalStateException("Monitor loop bound pattern changed");

        int widthPatches = 0;
        for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof IntInsnNode value && value.getOpcode() == Opcodes.SIPUSH && value.operand == 1280) {
                value.operand = 960;
                widthPatches++;
            }
        }
        if (widthPatches != 1) throw new IllegalStateException("Expected one 1280px capture cap, found " + widthPatches);

        int sendPatches = 0;
        for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.name.equals("sendBinary") &&
                call.desc.equals("(Ljava/nio/ByteBuffer;Z)Ljava/util/concurrent/CompletableFuture;")) {
                AbstractInsnNode pop = nextReal(n);
                if (pop == null || pop.getOpcode() != Opcodes.POP)
                    throw new IllegalStateException("sendBinary return pattern changed");
                InsnList waitForSend = new InsnList();
                waitForSend.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false));
                method.instructions.insertBefore(pop, waitForSend);
                sendPatches++;
            }
        }
        if (sendPatches != 1) throw new IllegalStateException("Expected one sendBinary call, found " + sendPatches);

        int pacingPatches = 0;
        for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.owner.equals("java/lang/Thread") &&
                call.name.equals("sleep") && call.desc.equals("(J)V")) {
                AbstractInsnNode constant = n.getPrevious();
                while (constant != null && (constant instanceof LabelNode || constant instanceof LineNumberNode ||
                        constant instanceof FrameNode)) constant = constant.getPrevious();
                if (constant instanceof LdcInsnNode ldc && Long.valueOf(120L).equals(ldc.cst)) {
                    method.instructions.insertBefore(constant,
                            new MethodInsnNode(Opcodes.INVOKESTATIC, "com/saturn/remote/StreamPacer",
                                    "awaitNextFrame", "()V", false));
                    method.instructions.remove(constant);
                    method.instructions.remove(n);
                    pacingPatches++;
                    break;
                }
            }
        }
        if (pacingPatches != 1) throw new IllegalStateException("20ms frame pacing call not found");

        boolean beginInserted = false;
        for (AbstractInsnNode n = method.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode call && call.owner.equals("com/saturn/remote/HostState") &&
                call.name.equals("getRunning") && call.desc.equals("()Z")) {
                AbstractInsnNode branch = nextReal(n);
                if (!(branch instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ)
                    throw new IllegalStateException("Host running loop guard changed");
                method.instructions.insert(branch, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/saturn/remote/StreamPacer", "beginFrame", "()V", false));
                beginInserted = true;
                break;
            }
        }
        if (!beginInserted) throw new IllegalStateException("Frame pacing start point not found");

        ClassWriter writer = new ClassWriter(0);
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patchViewerListener(byte[] input) {
        ClassNode cn = new ClassNode();
        new ClassReader(input).accept(cn, 0);
        MethodNode method = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("onBinary") &&
                m.desc.equals("(Ljava/net/http/WebSocket;Ljava/nio/ByteBuffer;Z)Ljava/util/concurrent/CompletionStage;")) {
                method = m;
                break;
            }
        }
        if (method == null) throw new IllegalStateException("Viewer listener onBinary not found");

        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VIEWER_LISTENER.substring(0, VIEWER_LISTENER.length()-6),
                "this$0", "Lcom/saturn/remote/ViewerWindow;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VIEWER_LISTENER.substring(0, VIEWER_LISTENER.length()-6),
                "this$0", "Lcom/saturn/remote/ViewerWindow;"));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "com/saturn/remote/ViewerWindow",
                "frameBuffer", "Ljava/io/ByteArrayOutputStream;"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/saturn/remote/FrameRenderer",
                "onBinary", "(Lcom/saturn/remote/ViewerWindow;Ljava/nio/ByteBuffer;ZLjava/io/ByteArrayOutputStream;)V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.LCONST_1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/net/http/WebSocket",
                "request", "(J)V", true));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 6;
        method.maxLocals = 4;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patchJar(byte[] jarBytes, String mode, byte[] helper) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jarBytes));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] data = zin.readAllBytes();
                if (mode.equals("host") && name.equals(HOST_CLASS)) {
                    data = patchHostClass(data); seen.add(HOST_CLASS);
                }
                if (mode.equals("viewer") && name.equals(VIEWER_LISTENER)) {
                    data = patchViewerListener(data); seen.add(VIEWER_LISTENER);
                }
                ZipEntry next = new ZipEntry(name);
                next.setTime(entry.getTime());
                zout.putNextEntry(next);
                zout.write(data);
                zout.closeEntry();
            }
            if (seen.size() != 1) throw new IllegalStateException("Expected one target class, patched " + seen);
            String helperName = mode.equals("host") ? PACER_CLASS : RENDERER_CLASS;
            zout.putNextEntry(new ZipEntry(helperName));
            zout.write(helper);
            zout.closeEntry();
        }
        byte[] result = out.toByteArray();
        try (ZipInputStream check = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry entry;
            while ((entry = check.getNextEntry()) != null) {
                while (check.read(new byte[8192]) >= 0) {}
            }
        }
        return result;
    }

    private static void patchPackage(Path input, Path output, String mode, Path helper) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean found = false;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(input));
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(output))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] data = zin.readAllBytes();
                if (name.equals(APP_JAR)) {
                    data = patchJar(data, mode, Files.readAllBytes(helper));
                    found = true;
                }
                ZipEntry next = new ZipEntry(name);
                next.setTime(entry.getTime());
                zout.putNextEntry(next);
                zout.write(data);
                zout.closeEntry();
            }
        }
        if (!found) throw new IllegalStateException("Missing embedded application JAR");
        try (ZipFile check = new ZipFile(output.toFile())) {
            if (check.getEntry(APP_JAR) == null) throw new IllegalStateException("Missing nested JAR after patch");
            try (ZipInputStream jar = new ZipInputStream(check.getInputStream(check.getEntry(APP_JAR)))) {
                ZipEntry entry;
                boolean helperFound = false;
                while ((entry = jar.getNextEntry()) != null) {
                    if (entry.getName().equals(mode.equals("host") ? PACER_CLASS : RENDERER_CLASS)) helperFound = true;
                    while (jar.read(new byte[8192]) >= 0) {}
                }
                if (!helperFound) throw new IllegalStateException("Helper class was not injected");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[0].equals("host") || args[0].equals("viewer")))
            throw new IllegalArgumentException("usage: JenyokStreamPatcher host|viewer input.zip output.zip helper.class");
        patchPackage(Paths.get(args[1]), Paths.get(args[2]), args[0], Paths.get(args[3]));
        System.out.println("Patched and verified " + args[0] + " package: " + args[2]);
    }
}
