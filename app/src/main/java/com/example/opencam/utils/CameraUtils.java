package com.example.opencam.utils;

import android.util.Size;

import com.example.opencam.fragment.CameraFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by joe.chu on 1/14/24
 *
 * @author joe.chu@bytedance.com
 */
public class CameraUtils {
    public static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                    option.getHeight() >= height && option.getWidth() >= width) {
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }

    static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size s1, Size s2) {
            return Long.signum((long) s1.getWidth() * s1.getHeight() -
                    (long) s2.getWidth() * s2.getHeight());
        }
    }
}
