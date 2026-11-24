import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class PatchWindowsPackage {
  static final String APP_JAR = "app/saturn-pc-remote.jar";
  static final String VIEWER = "com/saturn/remote/ViewerWindow.class";
  static final String CONNECT = "com/saturn/remote/ViewerWindow$connect$1.class";

  static byte[] readAll(InputStream in) throws IOException { return in.readAllBytes(); }

  static byte[] patchClass(byte[] bytes, String className) throws Exception {
    ClassReader cr = new ClassReader(bytes);
    ClassNode cn = new ClassNode();
    cr.accept(cn, 0);
    int changed = 0;
    if (className.equals(VIEWER)) {
      changed += forceAgentGate(cn, "mainContent$lambda$32$lambda$31");
      changed += forceAgentGate(cn, "mainContent$lambda$36$lambda$35");
      changed += forceEnabledOnSetEnabled(cn, "rebuildRemoteScreenTabs$lambda$69$lambda$68$lambda$67");
    } else if (className.equals(CONNECT)) {
      changed += forceAgentGate(cn, "onText$lambda$36");
      changed += forceAgentGate(cn, "onText$lambda$37");
      changed += forceEnabledOnSetEnabled(cn, "onOpen");
    }
    if (changed == 0) throw new IllegalStateException("No expected Recovery bytecode changed in " + className);
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cn.accept(cw);
    return cw.toByteArray();
  }

  static MethodNode method(ClassNode cn, String name) {
    for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
    throw new IllegalStateException("Missing expected method " + cn.name + "." + name);
  }

  static int forceAgentGate(ClassNode cn, String methodName) {
    MethodNode m = method(cn, methodName);
    int n = 0;
    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
      AbstractInsnNode next = insn.getNext();
      boolean gate = insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD &&
          f.name.equals("hostAgentAvailable") && f.desc.equals("Z");
      gate = gate || (insn instanceof MethodInsnNode call &&
          call.getOpcode() == Opcodes.INVOKESTATIC &&
          call.name.equals("access$getHostAgentAvailable$p") &&
          call.desc.equals("(Lcom/saturn/remote/ViewerWindow;)Z"));
      if (gate) {
        InsnList replacement = new InsnList();
        replacement.add(new InsnNode(Opcodes.POP));
        replacement.add(new InsnNode(Opcodes.ICONST_1));
        m.instructions.insertBefore(insn, replacement);
        m.instructions.remove(insn);
        n++;
      }
      insn = next;
    }
    if (n != 1) {
      System.err.println("DEBUG method " + methodName + " descriptor " + m.desc);
      int index = 0;
      for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
        String detail = "";
        if (insn instanceof FieldInsnNode f) detail = " " + f.owner + "." + f.name + " " + f.desc;
        else if (insn instanceof MethodInsnNode call) detail = " " + call.owner + "." + call.name + call.desc;
        else if (insn instanceof JumpInsnNode jump) detail = " -> " + jump.label.getLabel();
        System.err.println(index + " opcode=" + insn.getOpcode() + " type=" + insn.getType() + detail);
      }
      throw new IllegalStateException(methodName + ": expected one agent gate, found " + n);
    }
    return n;
  }

  static int forceEnabledOnSetEnabled(ClassNode cn, String methodName) {
    MethodNode m = method(cn, methodName);
    int n = 0;
    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
      if (insn instanceof MethodInsnNode call && call.name.equals("setEnabled") && call.desc.equals("(Z)V")) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && (prev.getType() == AbstractInsnNode.LABEL ||
               prev.getType() == AbstractInsnNode.LINE || prev.getType() == AbstractInsnNode.FRAME)) prev = prev.getPrevious();
        if (prev != null && prev.getOpcode() == Opcodes.ICONST_0) {
          m.instructions.set(prev, new InsnNode(Opcodes.ICONST_1));
          n++;
        }
      }
    }
    if (n != 1) throw new IllegalStateException(methodName + ": expected one disabled Recovery button, found " + n);
    return n;
  }

  static byte[] patchJar(byte[] input) throws Exception {
    ByteArrayInputStream bin = new ByteArrayInputStream(input);
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    Set<String> found = new HashSet<>();
    try (ZipInputStream zin = new ZipInputStream(bin); ZipOutputStream zout = new ZipOutputStream(bout)) {
      ZipEntry e;
      while ((e = zin.getNextEntry()) != null) {
        String name = e.getName();
        byte[] data = readAll(zin);
        if (name.equals(VIEWER) || name.equals(CONNECT)) {
          data = patchClass(data, name);
          found.add(name);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC"))) continue;
        ZipEntry out = new ZipEntry(name);
        out.setTime(e.getTime());
        zout.putNextEntry(out);
        zout.write(data);
        zout.closeEntry();
      }
    }
    if (!found.contains(VIEWER) || !found.contains(CONNECT))
      throw new IllegalStateException("Windows JAR is missing expected Viewer classes: " + found);
    return bout.toByteArray();
  }

  static void patchPackage(Path input, Path output) throws Exception {
    try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(input));
         ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(output))) {
      ZipEntry e;
      boolean jarFound = false;
      while ((e = zin.getNextEntry()) != null) {
        String name = e.getName();
        byte[] data = readAll(zin);
        if (name.equals(APP_JAR)) {
          data = patchJar(data);
          jarFound = true;
        }
        ZipEntry out = new ZipEntry(name);
        out.setTime(e.getTime());
        zout.putNextEntry(out);
        zout.write(data);
        zout.closeEntry();
      }
      if (!jarFound) throw new IllegalStateException("Package does not contain " + APP_JAR);
    }
    try (ZipFile check = new ZipFile(output.toFile())) {
      if (check.getEntry(APP_JAR) == null) throw new IllegalStateException("Patched package verification failed");
    }
    System.out.println("PATCHED " + output + " (" + Files.size(output) + " bytes)");
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("usage: PatchWindowsPackage input.zip output.zip");
    patchPackage(Path.of(args[0]), Path.of(args[1]));
  }
}
