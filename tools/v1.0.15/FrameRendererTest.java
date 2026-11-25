package com.saturn.remote;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;

public final class FrameRendererTest {
    public static void main(String[] args) throws Exception {
        BufferedImage source = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, ((x ^ y) & 1) == 0 ? Color.BLUE.getRGB() : Color.ORANGE.getRGB());
            }
        }

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        if (!ImageIO.write(source, "jpg", jpeg)) throw new AssertionError("JPEG writer unavailable");

        byte[] encoded = jpeg.toByteArray();
        byte[] packet = ByteBuffer.allocate(4 + encoded.length).putInt(2).put(encoded).array();
        BufferedImage decoded = FrameRenderer.decodePacket(packet);
        if (decoded == null) throw new AssertionError("Prefixed JPEG packet was not decoded");
        if (decoded.getWidth() != 320 || decoded.getHeight() != 180) {
            throw new AssertionError("Unexpected decoded dimensions: "
                    + decoded.getWidth() + "x" + decoded.getHeight());
        }
        if (FrameRenderer.decodePacket(new byte[] {0, 0, 0, 2, 1, 2, 3}) != null) {
            throw new AssertionError("A packet without a JPEG marker should be rejected");
        }
        System.out.println("FrameRenderer test passed: monitor-index prefix stripped and JPEG decoded.");
    }
}
