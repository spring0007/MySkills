package com.example.myskills.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.myskills.R;
import com.example.myskills.ui.view.FMMarkView;

public class ProfileFragment extends Fragment {

    FMMarkView markView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        markView = view.findViewById(R.id.fmScaleView);

    }

    private void initFMScaleView() {
        // 初始化频率和 AM/FM
        float freq = 96.5f;  // 默认 AM 频率 1143 kHz
        String fmOrAm = "FM";   // 默认 AM 模式
        // 先设置模式，再设置频率，确保刻度计算正确
        markView.post(new Runnable() {
            @Override
            public void run() {
                if ("FM".equals(fmOrAm)) {
                    markView.setRadioMode(FMMarkView.RadioMode.FM);
                } else {
                    markView.setRadioMode(FMMarkView.RadioMode.AM);
                }
                markView.setBandFrequency(freq);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(markView != null)
            markView.release();
    }
}
