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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class ViewerRecoveryPatcher {
    private static final String APP_JAR = "app/saturn-pc-remote.jar";
    private static final String VIEWER_CLASS = "com/saturn/remote/ViewerWindow.class";
    private static final String BRIDGE = "com/saturn/remote/ViewerRecoveryBridge.class";
    private static final String OWNER = "com/saturn/remote/ViewerWindow";
    private static final String BRIDGE_OWNER = "com/saturn/remote/ViewerRecoveryBridge";

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        throw new IllegalStateException("Missing Viewer method " + name + desc);
    }

    private static void appendUpdate(MethodNode method) {
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(BRIDGE_OWNER)
                    && call.name.equals("updateButton")) return;
        }
        boolean inserted = false;
        for (var insn = method.instructions.getFirst(); insn != null; ) {
            var next = insn.getNext();
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList update = new InsnList();
                update.add(new VarInsnNode(Opcodes.ALOAD, 0));
                update.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "updateButton",
                        "(L" + OWNER + ";)V", false));
                method.instructions.insertBefore(insn, update);
                inserted = true;
            }
            insn = next;
        }
        if (!inserted) throw new IllegalStateException("No RETURN in " + method.name);
    }

    private static byte[] patchViewer(byte[] input) {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);

        MethodNode click = method(node, "mainContent$lambda$32$lambda$31",
                "(L" + OWNER + ";Ljava/awt/event/ActionEvent;)V");
        click.instructions.clear();
        click.tryCatchBlocks.clear();
        if (click.localVariables != null) click.localVariables.clear();
        click.visibleLocalVariableAnnotations = null;
        click.invisibleLocalVariableAnnotations = null;
        click.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        click.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE_OWNER, "recover",
                "(L" + OWNER + ";)V", false));
        click.instructions.add(new InsnNode(Opcodes.RETURN));
        click.maxStack = 1;
        click.maxLocals = 2;

        appendUpdate(method(node, "access$setConnected$p", "(L" + OWNER + ";Z)V"));
        appendUpdate(method(node, "access$setHostAgentAvailable$p", "(L" + OWNER + ";Z)V"));
        appendUpdate(method(node, "access$setHostSessionOnline$p", "(L" + OWNER + ";Z)V"));

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
                if (name.equals(VIEWER_CLASS)) {
                    bytes = patchViewer(bytes);
                    seen.add(VIEWER_CLASS);
                }
                if (name.equals(BRIDGE)) throw new IllegalStateException("Viewer Recovery bridge already exists");
                ZipEntry next = new ZipEntry(name);
                next.setTime(entry.getTime());
                zip.putNextEntry(next);
                zip.write(bytes);
                zip.closeEntry();
            }
            if (!seen.contains(VIEWER_CLASS)) throw new IllegalStateException("ViewerWindow.class not found");
            zip.putNextEntry(new ZipEntry(BRIDGE));
            zip.write(bridgeBytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static void patchPackage(Path input, Path output, Path bridgeClass) throws Exception {
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
        if (!found) throw new IllegalStateException("Embedded Viewer application JAR not found");
        try (java.util.zip.ZipFile check = new java.util.zip.ZipFile(output.toFile())) {
            ZipEntry app = check.getEntry(APP_JAR);
            if (app == null) throw new IllegalStateException("Patched Viewer app JAR missing");
            try (ZipInputStream nested = new ZipInputStream(check.getInputStream(app))) {
                boolean bridgeFound = false;
                boolean viewerFound = false;
                ZipEntry entry;
                while ((entry = nested.getNextEntry()) != null) {
                    if (entry.getName().equals(BRIDGE)) bridgeFound = true;
                    if (entry.getName().equals(VIEWER_CLASS)) viewerFound = true;
                    while (nested.read(new byte[8192]) >= 0) {}
                }
                if (!bridgeFound || !viewerFound) throw new IllegalStateException("Viewer Recovery patch verification failed");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: ViewerRecoveryPatcher input.zip output.zip ViewerRecoveryBridge.class");
        }
        patchPackage(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        System.out.println("Patched Viewer local Recovery routing: " + args[1]);
    }
}
