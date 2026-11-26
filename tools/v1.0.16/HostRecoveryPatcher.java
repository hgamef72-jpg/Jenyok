import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class HostRecoveryPatcher {
    private static final String APP_JAR = "app/saturn-pc-remote.jar";
    private static final String LISTENER = "com/saturn/remote/HostState$connect$1.class";
    private static final String BRIDGE = "com/saturn/remote/HostRecoveryBridge.class";

    private static MethodNode method(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                if (found != null) throw new IllegalStateException("Duplicate method " + name + desc);
                found = method;
            }
        }
        if (found == null) throw new IllegalStateException("Missing method " + name + desc);
        return found;
    }

    private static byte[] patchListener(byte[] input) {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        MethodNode onOpen = method(node, "onOpen", "(Ljava/net/http/WebSocket;)V");
        MethodNode onText = method(node, "onText",
                "(Ljava/net/http/WebSocket;Ljava/lang/CharSequence;Z)Ljava/util/concurrent/CompletionStage;");
        String owner = "com/saturn/remote/HostState$connect$1";
        String hostState = "com/saturn/remote/HostState";
        String bridge = "com/saturn/remote/HostRecoveryBridge";

        boolean readyAlreadyPatched = false;
        for (var insn = onOpen.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(bridge)
                    && call.name.equals("advertiseReady")) readyAlreadyPatched = true;
        }
        if (!readyAlreadyPatched) {
            MethodInsnNode inventory = null;
            for (var insn = onOpen.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(hostState)
                        && call.name.equals("sendScreenInventory") && call.desc.equals("()V")) {
                    inventory = call;
                    break;
                }
            }
            if (inventory == null) throw new IllegalStateException("Host onOpen screen inventory call not found");
            InsnList advertise = new InsnList();
            advertise.add(new VarInsnNode(Opcodes.ALOAD, 0));
            advertise.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "this$0", "L" + hostState + ";"));
            advertise.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "advertiseReady",
                    "(L" + hostState + ";)V", false));
            onOpen.instructions.insert(inventory, advertise);
        }

        boolean handlerAlreadyPatched = false;
        for (var insn = onText.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(bridge)
                    && call.name.equals("handleMessage")) handlerAlreadyPatched = true;
        }
        if (!handlerAlreadyPatched) {
            InsnList handle = new InsnList();
            handle.add(new VarInsnNode(Opcodes.ALOAD, 0));
            handle.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "this$0", "L" + hostState + ";"));
            handle.add(new VarInsnNode(Opcodes.ALOAD, 2));
            handle.add(new MethodInsnNode(Opcodes.INVOKESTATIC, bridge, "handleMessage",
                    "(L" + hostState + ";Ljava/lang/CharSequence;)V", false));
            onText.instructions.insert(handle);
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patchJar(byte[] jarBytes, byte[] bridgeBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Set<String> seen = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jarBytes));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = input.readAllBytes();
                if (name.equals(LISTENER)) {
                    bytes = patchListener(bytes);
                    seen.add(LISTENER);
                }
                if (name.equals(BRIDGE)) throw new IllegalStateException("Recovery bridge already exists in baseline");
                ZipEntry next = new ZipEntry(name);
                next.setTime(entry.getTime());
                zip.putNextEntry(next);
                zip.write(bytes);
                zip.closeEntry();
            }
            if (!seen.contains(LISTENER)) throw new IllegalStateException("HostState WebSocket listener not found");
            zip.putNextEntry(new ZipEntry(BRIDGE));
            zip.write(bridgeBytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static void patchPackage(Path input, Path output, Path bridgeClass) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        boolean found = false;
        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(input));
             ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(output))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = zipIn.readAllBytes();
                if (name.equals(APP_JAR)) {
                    bytes = patchJar(bytes, Files.readAllBytes(bridgeClass));
                    found = true;
                }
                ZipEntry next = new ZipEntry(name);
                next.setTime(entry.getTime());
                zipOut.putNextEntry(next);
                zipOut.write(bytes);
                zipOut.closeEntry();
            }
        }
        if (!found) throw new IllegalStateException("Embedded application JAR not found");
        try (java.util.zip.ZipFile check = new java.util.zip.ZipFile(output.toFile())) {
            if (check.getEntry(APP_JAR) == null) throw new IllegalStateException("Patched app JAR missing");
            try (ZipInputStream nested = new ZipInputStream(check.getInputStream(check.getEntry(APP_JAR)))) {
                boolean bridgeFound = false;
                boolean listenerFound = false;
                ZipEntry entry;
                while ((entry = nested.getNextEntry()) != null) {
                    if (entry.getName().equals(BRIDGE)) bridgeFound = true;
                    if (entry.getName().equals(LISTENER)) listenerFound = true;
                    while (nested.read(new byte[8192]) >= 0) {}
                }
                if (!bridgeFound || !listenerFound) throw new IllegalStateException("Recovery patch verification failed");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: HostRecoveryPatcher input.zip output.zip HostRecoveryBridge.class");
        }
        patchPackage(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("Patched Host Recovery fallback: " + args[1]);
    }
}
