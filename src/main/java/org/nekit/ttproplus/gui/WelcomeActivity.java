package org.nekit.ttproplus.gui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.nekit.ttproplus.R;
import org.nekit.ttproplus.data.Preferences;

public class WelcomeActivity extends AppCompatActivity {

    private ViewPager viewPager;
    private LinearLayout dotsContainer;
    private Button skipBtn, nextBtn;
    private WelcomePagerAdapter adapter;
    private SharedPreferences prefs;

    private static final int PAGE_GREETING = 0;
    private static final int PAGE_SETTINGS = 1;
    private static final int PAGE_IMPORT = 2;
    private static final int PAGE_COUNT = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        viewPager = findViewById(R.id.welcome_pager);
        dotsContainer = findViewById(R.id.welcome_dots_container);
        skipBtn = findViewById(R.id.welcome_skip_btn);
        nextBtn = findViewById(R.id.welcome_next_btn);

        adapter = new WelcomePagerAdapter(getSupportFragmentManager());
        viewPager.setAdapter(adapter);

        setupDots(0);

        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                setupDots(position);
                if (position == PAGE_COUNT - 1) {
                    nextBtn.setText(R.string.welcome_finish);
                } else {
                    nextBtn.setText(R.string.welcome_next);
                }
            }
        });

        skipBtn.setOnClickListener(v -> finishWelcome());

        nextBtn.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current == PAGE_SETTINGS) {
                saveBasicSettings();
            }
            if (current < PAGE_COUNT - 1) {
                viewPager.setCurrentItem(current + 1);
            } else {
                saveBasicSettings();
                finishWelcome();
            }
        });
    }

    private void setupDots(int currentPosition) {
        dotsContainer.removeAllViews();
        for (int i = 0; i < PAGE_COUNT; i++) {
            TextView dot = new TextView(this);
            dot.setText("\u25cf");
            dot.setTextSize(14);
            dot.setPadding(8, 0, 8, 0);
            if (i == currentPosition) {
                dot.setTextColor(getResources().getColor(android.R.color.white));
            } else {
                dot.setTextColor(getResources().getColor(android.R.color.darker_gray));
            }
            dotsContainer.addView(dot);
        }
    }

    private void saveBasicSettings() {
        SettingsFragment settingsFragment = adapter.getSettingsFragment();
        if (settingsFragment != null) {
            settingsFragment.saveSettings(prefs);
        }
    }

    private void finishWelcome() {
        prefs.edit().putBoolean(Preferences.PREF_WELCOME_SHOWN, true).apply();
        finish();
    }

    @Override
    public void onBackPressed() {
        int current = viewPager.getCurrentItem();
        if (current > 0) {
            viewPager.setCurrentItem(current - 1);
        } else {
            finishWelcome();
        }
    }

    public static class GreetingFragment extends Fragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            return inflater.inflate(R.layout.fragment_welcome_greeting, container, false);
        }
    }

    public static class SettingsFragment extends Fragment {
        private EditText nicknameEdit;
        private CheckBox genderFemale;
        private CheckBox keepScreenOn;
        private CheckBox vibrate;

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_welcome_settings, container, false);
            nicknameEdit = view.findViewById(R.id.welcome_nickname);
            genderFemale = view.findViewById(R.id.welcome_gender_female);
            keepScreenOn = view.findViewById(R.id.welcome_keep_screen_on);
            vibrate = view.findViewById(R.id.welcome_vibrate);
            return view;
        }

        public void saveSettings(SharedPreferences prefs) {
            if (nicknameEdit == null) return;
            SharedPreferences.Editor editor = prefs.edit();
            String nickname = nicknameEdit.getText().toString().trim();
            if (!nickname.isEmpty()) {
                editor.putString(Preferences.PREF_GENERAL_NICKNAME, nickname);
            }
            editor.putBoolean(Preferences.PREF_GENERAL_GENDER, genderFemale.isChecked());
            editor.putBoolean("keep_screen_on_checkbox", keepScreenOn.isChecked());
            editor.putBoolean("vibrate_checkbox", vibrate.isChecked());
            editor.apply();
        }
    }

    public static class ImportFragment extends Fragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            return inflater.inflate(R.layout.fragment_welcome_import, container, false);
        }
    }

    private class WelcomePagerAdapter extends FragmentPagerAdapter {
        private SettingsFragment settingsFragment;

        public WelcomePagerAdapter(FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case PAGE_SETTINGS:
                    settingsFragment = new SettingsFragment();
                    return settingsFragment;
                case PAGE_IMPORT:
                    return new ImportFragment();
                default:
                    return new GreetingFragment();
            }
        }

        @Override
        public int getCount() {
            return PAGE_COUNT;
        }

        public SettingsFragment getSettingsFragment() {
            return settingsFragment;
        }
    }
}
