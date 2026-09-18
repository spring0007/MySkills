package com.example.myskills;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.example.myskills.fragments.HanziWriterFragment;
import com.example.myskills.fragments.HanziWriterFragment2;
import com.example.myskills.fragments.HomeFragment;
import com.example.myskills.fragments.ProfileFragment;
import com.example.myskills.fragments.SettingsFragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private DrawerLayout drawerLayout;
    private NavigationView navView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        navView = findViewById(R.id.nav_view);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);

        // 顶部左侧按钮：抽屉关闭时显示汉堡图标，抽屉打开时显示返回键(back)
        // 点击即可打开/关闭抽屉
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.nav_open, R.string.nav_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navView.setNavigationItemSelectedListener(this);

        // 系统返回键：抽屉打开时优先关闭抽屉
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // 默认显示首页 Fragment
        if (savedInstanceState == null) {
            showFragment(new HomeFragment());
            navView.setCheckedItem(R.id.nav_home);
        }
    }

    /**
     * 切换 Fragment
     */
    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, fragment)
                .commit();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_home) {
            showFragment(new HomeFragment());
        } else if (id == R.id.nav_profile) {
            showFragment(new ProfileFragment());
        } else if (id == R.id.nav_settings) {
            showFragment(new SettingsFragment());
        }else if (id == R.id.nav_hanzi_writer) {
            showFragment(new HanziWriterFragment());
        }else if (id == R.id.nav_hanzi_writer2) {
            showFragment(new HanziWriterFragment2());
        }
        // 选择后关闭抽屉
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }
}
