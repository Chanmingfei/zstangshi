import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 把安卓的校徽 PNG 居中缩放并补白成 iOS 要求的 1024×1024 不透明方图。 */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int size = 1024;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // iOS 要求图标不透明：先铺白，再把带透明通道的校徽叠上去
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        double scale = Math.min((double) size / src.getWidth(), (double) size / src.getHeight());
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        g.drawImage(src, (size - w) / 2, (size - h) / 2, w, h, null);
        g.dispose();
        File dst = new File(args[1]);
        dst.getParentFile().mkdirs();
        ImageIO.write(out, "png", dst);
        System.out.println("wrote " + dst + " " + dst.length() + " bytes");
    }
}
