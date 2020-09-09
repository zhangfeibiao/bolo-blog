package org.b3log.solo.util;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Created by zhangfeibiao on 2020/9/8.
 */
public class ImageExif {

    public static byte[] readImageToByteArray(String imageUrl) {
        URL url = null;
        InputStream inputStream = null;
        ByteArrayOutputStream outputStream = null;
        try {
            url = new URL(imageUrl);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setDoInput(true);
            urlConnection.setRequestMethod("GET");
            urlConnection.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)");
            urlConnection.setRequestProperty("referer", "https://blog.zhangfeibiao.com/admin-index.do");


            inputStream = urlConnection.getInputStream();
            outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len = 0;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }

            return outputStream.toByteArray();
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                }
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                }
            }
        }

        return null;
    }

    public static void main(String[] args) {
        String imageUrl = "https://b3logfile.com/file/2020/06/IMG20191216134916-7495fb9f.jpg?imageView2/2/w/1280/format/jpg/interlace/1/q/100";
        byte[] bytes = ImageExif.readImageToByteArray(imageUrl);
        try {
            ImageExif.removeExif(bytes);
            final ImageMetadata metadata = Imaging.getMetadata(bytes);
            final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
            final TiffImageMetadata exif = jpegMetadata.getExif();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    public static byte[] removeExif(final byte[] bytes) throws Exception {
        try (final ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            TiffOutputSet outputSet = null;
            final ImageMetadata metadata = Imaging.getMetadata(bytes);
            if (!(metadata instanceof JpegImageMetadata)) {
                return bytes;
            }

            final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
            final TiffImageMetadata exif = jpegMetadata.getExif();
            if (null != exif) {
                outputSet = exif.getOutputSet();
            }

            if (null == outputSet) {
                return bytes;
            }

            final List<TiffOutputDirectory> directories = outputSet.getDirectories();
            for (final TiffOutputDirectory directory : directories) {
                final List<TiffOutputField> fields = directory.getFields();
                for (final TiffOutputField field : fields) {
                    if (!StringUtils.equalsIgnoreCase("Orientation", field.tagInfo.name)) {
                        outputSet.removeField(field.tagInfo);
                    }
                }
            }

            new ExifRewriter().updateExifMetadataLossless(bytes, os, outputSet);
            return os.toByteArray();
        }
    }


}
