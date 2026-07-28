package org.nekit.ttproplus.gui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.nekit.ttproplus.R;

public class CustomFilePickerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FOLDER_MODE = "folder_mode";
    private static final int PERMISSION_REQUEST_CODE = 1200;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 1201;

    private TextView txtCurrentPath;
    private ListView fileListView;
    private EditText searchEditText;
    private Button btnSearchTt;
    private Button btnSelectFolder;
    private ImageButton btnBack;
    private ProgressBar searchProgress;
    private TextView txtSearchStatus;
    private File currentDir;
    private FileAdapter adapter;
    private List<FileItem> filesList = new ArrayList<>();
    private boolean isShowingSearchResults = false;
    private boolean isFolderMode = false;

    /** Wraps a File with optional display metadata for search results. */
    private static class FileItem {
        final File file;
        final boolean isSearchResult;

        FileItem(File file, boolean isSearchResult) {
            this.file = file;
            this.isSearchResult = isSearchResult;
        }

        FileItem(File file) {
            this(file, false);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_file_picker);
        EdgeToEdgeHelper.enableEdgeToEdge(this);

        txtCurrentPath = findViewById(R.id.txt_current_path);
        fileListView = findViewById(R.id.file_list_view);
        btnSearchTt = findViewById(R.id.btn_search_tt);
        btnSelectFolder = findViewById(R.id.btn_select_folder);
        btnBack = findViewById(R.id.btn_back);
        searchEditText = findViewById(R.id.search_edit_text);
        searchProgress = findViewById(R.id.search_progress);
        txtSearchStatus = findViewById(R.id.txt_search_status);

        isFolderMode = getIntent().getBooleanExtra(EXTRA_FOLDER_MODE, false);

        if (isFolderMode) {
            btnSelectFolder.setVisibility(View.VISIBLE);
            btnSelectFolder.setOnClickListener(v -> selectFolder(currentDir));
        }

        btnSearchTt.setOnClickListener(v -> searchTtFiles());

        btnBack.setOnClickListener(v -> {
            if (isShowingSearchResults) {
                isShowingSearchResults = false;
                txtSearchStatus.setVisibility(View.GONE);
                searchEditText.setText("");
                searchEditText.setVisibility(View.GONE);
                loadDir();
            } else {
                onBackPressed();
            }
        });

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (isShowingSearchResults && lastSearchResults != null) {
                    filterSearchResults(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        adapter = new FileAdapter();
        fileListView.setAdapter(adapter);

        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            FileItem item = filesList.get(position);
            File selected = item.file;
            if (selected.getName().equals("..")) {
                currentDir = currentDir.getParentFile();
                isShowingSearchResults = false;
                txtSearchStatus.setVisibility(View.GONE);
                searchEditText.setVisibility(View.GONE);
                searchEditText.setText("");
                loadDir();
            } else if (selected.isDirectory()) {
                currentDir = selected;
                isShowingSearchResults = false;
                txtSearchStatus.setVisibility(View.GONE);
                searchEditText.setVisibility(View.GONE);
                searchEditText.setText("");
                loadDir();
            } else if (!isFolderMode) {
                selectFile(selected);
            }
        });

        // Start from external storage
        currentDir = Environment.getExternalStorageDirectory();
        if (currentDir == null || !currentDir.exists()) {
            currentDir = new File("/");
        }

        if (checkPermission()) {
            loadDir();
        } else {
            requestPermission();
        }
    }

    private boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On Android 11+, request MANAGE_EXTERNAL_STORAGE via Settings
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadDir();
            } else {
                Toast.makeText(this, R.string.permission_required_files, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    loadDir();
                } else {
                    Toast.makeText(this, R.string.permission_required_files, Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        }
    }

    private void loadDir() {
        isShowingSearchResults = false;
        txtCurrentPath.setText(currentDir.getAbsolutePath());
        txtCurrentPath.setVisibility(View.VISIBLE);
        txtSearchStatus.setVisibility(View.GONE);
        searchEditText.setVisibility(View.GONE);
        searchEditText.setText("");
        filesList.clear();

        File[] files = currentDir.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> normalFiles = new ArrayList<>();

        if (currentDir.getParentFile() != null && !currentDir.getAbsolutePath().equals("/")) {
            filesList.add(new FileItem(new File(currentDir, "..")));
        }

        if (files != null) {
            for (File f : files) {
                if (f.isHidden()) continue;
                if (f.isDirectory()) {
                    dirs.add(f);
                } else {
                    normalFiles.add(f);
                }
            }
        }

        Comparator<File> fileComparator = (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName());
        Collections.sort(dirs, fileComparator);
        Collections.sort(normalFiles, fileComparator);

        for (File d : dirs) filesList.add(new FileItem(d));
        for (File f : normalFiles) filesList.add(new FileItem(f));
        adapter.notifyDataSetChanged();
    }

    void selectFile(File file) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    void selectFolder(File folder) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_FILE_PATH, folder.getAbsolutePath());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private List<FileItem> lastSearchResults = null;

    private void searchTtFiles() {
        searchProgress.setVisibility(View.VISIBLE);
        txtSearchStatus.setVisibility(View.VISIBLE);
        txtSearchStatus.setText(R.string.searching_tt_files);
        btnSearchTt.setEnabled(false);

        new SearchTask().execute();
    }

    private void filterSearchResults(String query) {
        if (lastSearchResults == null) return;
        filesList.clear();
        if (query.isEmpty()) {
            filesList.addAll(lastSearchResults);
        } else {
            String lowerQuery = query.toLowerCase();
            for (FileItem item : lastSearchResults) {
                if (item.file.getName().toLowerCase().contains(lowerQuery) ||
                    item.file.getParent().toLowerCase().contains(lowerQuery)) {
                    filesList.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateSearchStatus();
    }

    private void updateSearchStatus() {
        if (isShowingSearchResults) {
            txtSearchStatus.setText(getString(R.string.tt_files_found_count, filesList.size()));
        }
    }

    @Override
    public void onBackPressed() {
        if (isShowingSearchResults) {
            isShowingSearchResults = false;
            txtSearchStatus.setVisibility(View.GONE);
            searchEditText.setVisibility(View.GONE);
            searchEditText.setText("");
            lastSearchResults = null;
            loadDir();
        } else if (currentDir.getParentFile() != null && !currentDir.getAbsolutePath().equals("/") && !currentDir.getAbsolutePath().equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            currentDir = currentDir.getParentFile();
            loadDir();
        } else {
            super.onBackPressed();
        }
    }

    private class SearchTask extends AsyncTask<Void, String, List<File>> {

        @Override
        protected List<File> doInBackground(Void... voids) {
            List<File> found = new ArrayList<>();

            // Search external storage
            File extStorage = Environment.getExternalStorageDirectory();
            if (extStorage != null && extStorage.exists()) {
                scanDir(extStorage, found);
            }

            // Also search common additional storage paths
            File[] additionalRoots = {
                new File("/storage"),
                new File("/sdcard"),
            };
            for (File root : additionalRoots) {
                if (root.exists() && root.isDirectory() && !root.equals(extStorage)) {
                    scanDir(root, found);
                }
            }

            return found;
        }

        private void scanDir(File dir, List<File> found) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return;

            String name = dir.getName().toLowerCase();
            // Skip system/hidden/cache directories
            if (name.startsWith(".") || name.equals("android") || name.equals("obb") || name.equals("data") || name.equals("cache")) {
                return;
            }

            File[] files = dir.listFiles();
            if (files == null) return;

            for (File f : files) {
                if (f.isDirectory()) {
                    scanDir(f, found);
                } else if (f.getName().toLowerCase().endsWith(".tt")) {
                    found.add(f);
                }
            }
        }

        @Override
        protected void onPostExecute(List<File> files) {
            searchProgress.setVisibility(View.GONE);
            btnSearchTt.setEnabled(true);

            if (files.isEmpty()) {
                Toast.makeText(CustomFilePickerActivity.this, R.string.no_tt_files_found, Toast.LENGTH_SHORT).show();
                txtSearchStatus.setText(R.string.no_tt_files_found);
                return;
            }

            // Sort by name
            Collections.sort(files, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

            // Show results in the list
            isShowingSearchResults = true;
            txtCurrentPath.setVisibility(View.GONE);
            searchEditText.setVisibility(View.VISIBLE);
            searchEditText.setText("");

            lastSearchResults = new ArrayList<>();
            for (File f : files) {
                lastSearchResults.add(new FileItem(f, true));
            }

            filesList.clear();
            filesList.addAll(lastSearchResults);
            adapter.notifyDataSetChanged();

            updateSearchStatus();
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private class FileAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return filesList.size();
        }

        @Override
        public Object getItem(int position) {
            return filesList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(CustomFilePickerActivity.this)
                        .inflate(R.layout.item_custom_file, parent, false);
            }

            ImageView imgIcon = convertView.findViewById(R.id.item_icon);
            TextView txtName = convertView.findViewById(R.id.item_name);
            TextView txtDetails = convertView.findViewById(R.id.item_details);

            FileItem item = filesList.get(position);
            File file = item.file;
            String name = file.getName();
            txtName.setText(name);

            if (name.equals("..")) {
                imgIcon.setImageResource(R.drawable.ic_folder);
                txtDetails.setVisibility(View.GONE);
                convertView.setContentDescription(getString(R.string.cd_up_folder));
            } else if (file.isDirectory()) {
                imgIcon.setImageResource(R.drawable.ic_folder);
                txtDetails.setVisibility(View.GONE);
                convertView.setContentDescription(name + ", " + getString(R.string.cd_folder));
            } else {
                if (name.toLowerCase().endsWith(".tt")) {
                    imgIcon.setImageResource(R.drawable.teamtalk_blue);
                    convertView.setContentDescription(name + ", " + getString(R.string.cd_tt_file));
                } else {
                    imgIcon.setImageResource(R.drawable.ic_file);
                    convertView.setContentDescription(name + ", " + getString(R.string.cd_file));
                }

                // Show file size and parent path for search results
                if (item.isSearchResult) {
                    String details = formatFileSize(file.length()) + "  •  " + file.getParent();
                    txtDetails.setText(details);
                    txtDetails.setVisibility(View.VISIBLE);
                } else {
                    String details = formatFileSize(file.length());
                    txtDetails.setText(details);
                    txtDetails.setVisibility(View.VISIBLE);
                }
            }

            return convertView;
        }
    }
}
