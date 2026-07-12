package com.winlator.star;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.star.R;
import com.winlator.star.inputcontrols.Binding;
import com.winlator.star.inputcontrols.ControlElement;
import com.winlator.star.inputcontrols.ControlsProfile;
import com.winlator.star.inputcontrols.InputControlsManager;
import com.winlator.star.inputcontrols.CustomIconManager;
import com.winlator.star.math.Mathf;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.UnitUtils;
import com.winlator.star.widget.AccentArrayAdapter;
import com.winlator.star.widget.InputControlsView;
import com.winlator.star.widget.NumberPicker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ControlsEditorActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int DEFAULT_GRID_ROWS = 2;
    private static final int DEFAULT_GRID_COLS = 8;

    private InputControlsView inputControlsView;
    private ControlsProfile profile;
    private CustomIconManager customIconManager;
    private ActivityResultLauncher<String> iconPickerLauncher;
    private ActivityResultLauncher<Intent> iconPickerInAppLauncher;
    private LinearLayout currentLLCustomIconList; // To refresh UI after picking

    // Background image picker
    private ActivityResultLauncher<String> bgImagePickerLauncher;
    private ActivityResultLauncher<Intent> bgImagePickerInAppLauncher;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        customIconManager = new CustomIconManager(this);
        inputControlsView = new InputControlsView(this);
        inputControlsView.setEditMode(true);
        inputControlsView.setOverlayOpacity(0.6f);

        profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, getIntent().getIntExtra("profile_id", 0)));
        ((TextView)findViewById(R.id.TVProfileName)).setText(profile.getName());
        inputControlsView.setProfile(profile);

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        container.findViewById(R.id.BTAddElement).setOnClickListener(this);
        container.findViewById(R.id.BTRemoveElement).setOnClickListener(this);
        container.findViewById(R.id.BTElementSettings).setOnClickListener(this);

        // Custom-icon pickers: the built-in file picker (primary) and the system SAF picker (secondary).
        iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) addCustomIconFromUri(uri);
        });
        iconPickerInAppLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String path = result.getData().getStringExtra(FilePickerActivity.EXTRA_SELECTED_FILE);
                if (path != null) addCustomIconFromUri(Uri.fromFile(new java.io.File(path)));
            }
        });

        // Background image pickers
        bgImagePickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) setBackgroundImageFromUri(uri);
        });
        bgImagePickerInAppLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String path = result.getData().getStringExtra(FilePickerActivity.EXTRA_SELECTED_FILE);
                if (path != null) setBackgroundImageFromUri(Uri.fromFile(new java.io.File(path)));
            }
        });
    }

    private void setBackgroundImageFromUri(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is != null) {
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                is.close();
                if (bitmap != null) {
                    inputControlsView.setBackgroundImage(bitmap);
                    AppUtils.showToast(this, "Background image set");
                }
            }
        } catch (IOException e) {
            AppUtils.showToast(this, "Failed to load image");
        }
    }

    private void showAddElementTypeDialog() {
        final String[] typeNames = new String[ControlElement.Type.values().length];
        for (int i = 0; i < typeNames.length; i++) {
            typeNames[i] = ControlElement.Type.values()[i].name().replace("_", " ");
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Select Control Type")
            .setItems(typeNames, (d, which) -> {
                ControlElement.Type type = ControlElement.Type.values()[which];
                if (inputControlsView.addElement(type)) {
                    // For BUTTON_GRID, immediately open settings to configure grid
                    if (type == ControlElement.Type.BUTTON_GRID) {
                        ControlElement el = inputControlsView.getSelectedElement();
                        if (el != null) showControlElementSettings(findViewById(R.id.BTElementSettings));
                    }
                } else {
                    AppUtils.showToast(this, R.string.no_profile_selected);
                }
            })
            .show();
    }

    private void showBackgroundImageDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Set Background Image")
            .setItems(new CharSequence[]{"Pick from files", "Clear background"}, (d, which) -> {
                if (which == 0) {
                    new android.app.AlertDialog.Builder(this)
                        .setItems(new CharSequence[]{"Browse files", "Pick via system…"}, (d2, which2) -> {
                            if (which2 == 0) {
                                Intent intent = new Intent(this, FilePickerActivity.class);
                                intent.putExtra(FilePickerActivity.EXTRA_EXTENSIONS, new String[]{"png", "jpg", "jpeg", "webp", "bmp"});
                                intent.putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, "Select background image");
                                bgImagePickerInAppLauncher.launch(intent);
                            } else {
                                bgImagePickerLauncher.launch("image/*");
                            }
                        })
                        .show();
                } else {
                    inputControlsView.setBackgroundImage(null);
                    AppUtils.showToast(this, "Background cleared");
                }
            })
            .show();
    }

    // Shared: add a custom icon from any Uri (file:// from the in-app picker, content:// from SAF).
    private void addCustomIconFromUri(Uri uri) {
        if (currentLLCustomIconList != null) {
            customIconManager.addCustomIcon(uri);
            loadCustomIcons(currentLLCustomIconList, inputControlsView.getSelectedElement().getIconId());
        }
    }

    // Two-option chooser: built-in picker first, then system SAF.
    private void promptPickCustomIcon() {
        new android.app.AlertDialog.Builder(this)
            .setItems(new CharSequence[]{"Browse files", "Pick via system…"}, (d, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(this, FilePickerActivity.class);
                    intent.putExtra(FilePickerActivity.EXTRA_EXTENSIONS, new String[]{"png", "jpg", "jpeg", "webp", "bmp", "gif"});
                    intent.putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, "Select icon image");
                    iconPickerInAppLauncher.launch(intent);
                } else {
                    iconPickerLauncher.launch("image/*");
                }
            })
            .show();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                showAddElementTypeDialog();
                break;
            case R.id.BTRemoveElement:
                if (!inputControlsView.removeElement()) AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
            case R.id.BTElementSettings:
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                if (selectedElement != null) showControlElementSettings(v);
                else AppUtils.showToast(this, R.string.no_control_element_selected);
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.controls_editor_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.menu_set_background) {
            showBackgroundImageDialog();
            return true;
        }
        else if (item.getItemId() == R.id.menu_bg_opacity) {
            showBackgroundOpacityDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showBackgroundOpacityDialog() {
        final LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(60, 30, 60, 0);

        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress((int)(inputControlsView.getBackgroundOpacity() * 100));
        ll.addView(seekBar);

        final TextView tv = new TextView(this);
        tv.setText("Opacity: " + (int)(inputControlsView.getBackgroundOpacity() * 100) + "%");
        ll.addView(tv);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float op = progress / 100f;
                inputControlsView.setBackgroundOpacity(op);
                tv.setText("Opacity: " + progress + "%");
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        new android.app.AlertDialog.Builder(this)
            .setTitle("Background Opacity")
            .setView(ll)
            .setPositiveButton("OK", null)
            .show();
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        View view = LayoutInflater.from(this).inflate(R.layout.control_element_settings, null);

        final Runnable updateLayout = () -> {
            ControlElement.Type type = element.getType();
            View llShape = view.findViewById(R.id.LLShape);
            View cbToggle = view.findViewById(R.id.CBToggleSwitch);
            View llCustom = view.findViewById(R.id.LLCustomTextIcon);
            View llRange = view.findViewById(R.id.LLRangeOptions);
            View llDynamicStick = view.findViewById(R.id.LLDynamicStickSettings);
            View llMouseArea = view.findViewById(R.id.LLMouseAreaSettings);
            View llButtonGrid = view.findViewById(R.id.LLButtonGridSettings);
            View llBindings = view.findViewById(R.id.LLBindings);

            if (llShape != null) llShape.setVisibility(type == ControlElement.Type.BUTTON ? View.VISIBLE : View.GONE);
            if (cbToggle != null) cbToggle.setVisibility(type == ControlElement.Type.BUTTON ? View.VISIBLE : View.GONE);
            if (llCustom != null) llCustom.setVisibility(type == ControlElement.Type.BUTTON ? View.VISIBLE : View.GONE);
            if (llRange != null) llRange.setVisibility(type == ControlElement.Type.RANGE_BUTTON ? View.VISIBLE : View.GONE);
            if (llDynamicStick != null) llDynamicStick.setVisibility(type == ControlElement.Type.DYNAMIC_STICK ? View.VISIBLE : View.GONE);
            if (llMouseArea != null) llMouseArea.setVisibility(type == ControlElement.Type.MOUSE_AREA ? View.VISIBLE : View.GONE);
            if (llButtonGrid != null) llButtonGrid.setVisibility(type == ControlElement.Type.BUTTON_GRID ? View.VISIBLE : View.GONE);
            // Hide bindings section for MOUSE_AREA (it controls mouse directly, no key mappings)
            if (llBindings != null) llBindings.setVisibility(type == ControlElement.Type.MOUSE_AREA ? View.GONE : View.VISIBLE);

            loadBindingSpinners(element, view);
        };

        loadTypeSpinner(element, view.findViewById(R.id.SType), updateLayout);
        loadShapeSpinner(element, view.findViewById(R.id.SShape));
        loadRangeSpinner(element, view.findViewById(R.id.SRange));

        RadioGroup rgOrientation = view.findViewById(R.id.RGOrientation);
        if (rgOrientation != null) {
            rgOrientation.check(element.getOrientation() == 1 ? R.id.RBVertical : R.id.RBHorizontal);
            rgOrientation.setOnCheckedChangeListener((group, checkedId) -> {
                element.setOrientation((byte)(checkedId == R.id.RBVertical ? 1 : 0));
                profile.save();
                inputControlsView.invalidate();
            });
        }

        NumberPicker npColumns = view.findViewById(R.id.NPColumns);
        if (npColumns != null) {
            npColumns.setValue(element.getBindingCount());
            npColumns.setOnValueChangeListener((numberPicker, value) -> {
                element.setBindingCount(value);
                profile.save();
                inputControlsView.invalidate();
            });
        }

        // --- Dynamic Stick sliders ---
        SeekBar sbAreaWidthStick = view.findViewById(R.id.SBAreaWidthStick);
        TextView tvAreaWidthStick = view.findViewById(R.id.TVAreaWidthStick);
        if (sbAreaWidthStick != null) {
            sbAreaWidthStick.setProgress(element.getAreaWidth());
            if (tvAreaWidthStick != null) tvAreaWidthStick.setText(element.getAreaWidth() + "px");
            sbAreaWidthStick.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    if (tvAreaWidthStick != null) tvAreaWidthStick.setText(val + "px");
                    if (fromUser) { element.setAreaWidth(val); profile.save(); inputControlsView.invalidate(); }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        SeekBar sbAreaHeightStick = view.findViewById(R.id.SBAreaHeightStick);
        TextView tvAreaHeightStick = view.findViewById(R.id.TVAreaHeightStick);
        if (sbAreaHeightStick != null) {
            sbAreaHeightStick.setProgress(element.getAreaHeight());
            if (tvAreaHeightStick != null) tvAreaHeightStick.setText(element.getAreaHeight() + "px");
            sbAreaHeightStick.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    if (tvAreaHeightStick != null) tvAreaHeightStick.setText(val + "px");
                    if (fromUser) { element.setAreaHeight(val); profile.save(); inputControlsView.invalidate(); }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        SeekBar sbStickRadius = view.findViewById(R.id.SBStickRadius);
        TextView tvStickRadius = view.findViewById(R.id.TVStickRadius);
        if (sbStickRadius != null) {
            sbStickRadius.setProgress(element.getStickRadius());
            if (tvStickRadius != null) tvStickRadius.setText(element.getStickRadius() + "px");
            sbStickRadius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    if (tvStickRadius != null) tvStickRadius.setText(val + "px");
                    if (fromUser) { element.setStickRadius(val); profile.save(); inputControlsView.invalidate(); }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // --- Mouse Area sliders ---
        SeekBar sbAreaWidthMouse = view.findViewById(R.id.SBAreaWidthMouse);
        TextView tvAreaWidthMouse = view.findViewById(R.id.TVAreaWidthMouse);
        if (sbAreaWidthMouse != null) {
            sbAreaWidthMouse.setProgress(element.getAreaWidth());
            if (tvAreaWidthMouse != null) tvAreaWidthMouse.setText(element.getAreaWidth() + "px");
            sbAreaWidthMouse.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    if (tvAreaWidthMouse != null) tvAreaWidthMouse.setText(val + "px");
                    if (fromUser) { element.setAreaWidth(val); profile.save(); inputControlsView.invalidate(); }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        SeekBar sbAreaHeightMouse = view.findViewById(R.id.SBAreaHeightMouse);
        TextView tvAreaHeightMouse = view.findViewById(R.id.TVAreaHeightMouse);
        if (sbAreaHeightMouse != null) {
            sbAreaHeightMouse.setProgress(element.getAreaHeight());
            if (tvAreaHeightMouse != null) tvAreaHeightMouse.setText(element.getAreaHeight() + "px");
            sbAreaHeightMouse.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    if (tvAreaHeightMouse != null) tvAreaHeightMouse.setText(val + "px");
                    if (fromUser) { element.setAreaHeight(val); profile.save(); inputControlsView.invalidate(); }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        SeekBar sbMouseSensitivity = view.findViewById(R.id.SBMouseSensitivity);
        TextView tvMouseSensitivity = view.findViewById(R.id.TVMouseSensitivity);
        if (sbMouseSensitivity != null) {
            int sensVal = Math.round(element.getMouseSensitivity() * 10);
            sbMouseSensitivity.setProgress(sensVal);
            if (tvMouseSensitivity != null) tvMouseSensitivity.setText(String.format("%.1fx", element.getMouseSensitivity()));
            sbMouseSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                    float sens = val / 10.0f;
                    if (tvMouseSensitivity != null) tvMouseSensitivity.setText(String.format("%.1fx", sens));
                    if (fromUser) { element.setMouseSensitivity(sens); profile.save(); }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }

        // --- Button Grid pickers ---
        NumberPicker npGridRows = view.findViewById(R.id.NPGridRows);
        if (npGridRows != null) {
            npGridRows.setValue(getGridRowsForEditor(element));
            npGridRows.setOnValueChangeListener((picker, val) -> {
                int cols = getGridColsForEditor(element);
                element.setGridRows(val);
                element.setBindingCount(val * cols);
                profile.save();
                inputControlsView.invalidate();
                loadBindingSpinners(element, view);
            });
        }

        NumberPicker npGridCols = view.findViewById(R.id.NPGridCols);
        if (npGridCols != null) {
            npGridCols.setValue(getGridColsForEditor(element));
            npGridCols.setOnValueChangeListener((picker, val) -> {
                int rows = getGridRowsForEditor(element);
                element.setGridCols(val);
                element.setBindingCount(rows * val);
                profile.save();
                inputControlsView.invalidate();
                loadBindingSpinners(element, view);
            });
        }

        // Grid cell shape spinner
        Spinner sGridCellShape = view.findViewById(R.id.SGridCellShape);
        if (sGridCellShape != null) {
            AccentArrayAdapter<String> shapeAdapter = new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, ControlElement.Shape.names());
            shapeAdapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
            sGridCellShape.setAdapter(shapeAdapter);
            sGridCellShape.setSelection(element.getGridCellShape().ordinal(), false);
            sGridCellShape.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                    element.setGridCellShape(ControlElement.Shape.values()[position]);
                    profile.save();
                    inputControlsView.invalidate();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // --- Quick Fill buttons for grid ---
        LinearLayout llQuickFill = view.findViewById(R.id.LLQuickFill);
        if (llQuickFill != null) {
            llQuickFill.removeAllViews();
            final View settingsView = view; // capture for lambda
            addQuickFillButton(llQuickFill, "Fill: QWERTY", () -> {
                fillGridQWERTY(element);
                loadBindingSpinners(element, settingsView);
            });
            addQuickFillButton(llQuickFill, "Fill: F1–F12", () -> {
                fillGridFKeys(element);
                loadBindingSpinners(element, settingsView);
            });
            addQuickFillButton(llQuickFill, "Fill: NumPad", () -> {
                fillGridNumPad(element);
                loadBindingSpinners(element, settingsView);
            });
            addQuickFillButton(llQuickFill, "Clear", () -> {
                prepareGridForFill(element);
                int total = getGridCellCountForEditor(element);
                for (int i = 0; i < total; i++) {
                    clearGridCell(element, i);
                }
                profile.save();
                inputControlsView.invalidate();
                loadBindingSpinners(element, settingsView);
            });
        }

        final TextView tvScale = view.findViewById(R.id.TVScale);
        SeekBar sbScale = view.findViewById(R.id.SBScale);
        if (sbScale != null) {
            sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvScale != null) tvScale.setText(progress+"%");
                    if (fromUser) {
                        progress = (int)Mathf.roundTo(progress, 5);
                        seekBar.setProgress(progress);
                        element.setScale(progress / 100.0f);
                        profile.save();
                        inputControlsView.invalidate();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            sbScale.setProgress((int)(element.getScale() * 100));
        }

        CheckBox cbToggleSwitch = view.findViewById(R.id.CBToggleSwitch);
        if (cbToggleSwitch != null) {
            cbToggleSwitch.setChecked(element.isToggleSwitch());
            cbToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                element.setToggleSwitch(isChecked);
                profile.save();
            });
        }

        final EditText etCustomText = view.findViewById(R.id.ETCustomText);
        if (etCustomText != null) etCustomText.setText(element.getText());

        // LOAD BOTH ICON LISTS
        final LinearLayout llIconList = view.findViewById(R.id.LLIconList);
        if (llIconList != null) loadIcons(llIconList, element.getIconId());

        currentLLCustomIconList = view.findViewById(R.id.LLCustomIconList);
        if (currentLLCustomIconList != null) loadCustomIcons(currentLLCustomIconList, element.getIconId());

        View btAddIcon = view.findViewById(R.id.BTAddCustomIcon);
        if (btAddIcon != null) btAddIcon.setOnClickListener(v -> promptPickCustomIcon());

        updateLayout.run();

        PopupWindow popupWindow = AppUtils.showPopupWindow(anchorView, view, 340, 0);
        popupWindow.setOnDismissListener(() -> {
            if (etCustomText != null) element.setText(etCustomText.getText().toString().trim());
            
            // Check both lists for selection
            short selectedIconId = 0;
            if (llIconList != null) selectedIconId = getSelectedIdFromList(llIconList);
            if (selectedIconId == 0 && currentLLCustomIconList != null) selectedIconId = getSelectedIdFromList(currentLLCustomIconList);

            element.setIconId((byte)selectedIconId);
            profile.save();
            inputControlsView.invalidate();
        });
    }

    private short getSelectedIdFromList(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isSelected()) return (short)child.getTag();
        }
        return 0;
    }

    private void loadIcons(final LinearLayout parent, int selectedId) {
        parent.removeAllViews();
        List<Byte> iconIds = new ArrayList<>();
        try {
            String[] filenames = getAssets().list("inputcontrols/icons/");
            for (String file : filenames) iconIds.add(Byte.parseByte(FileUtils.getBasename(file)));
        } catch (Exception e) {}
        Collections.sort(iconIds);
        addIconViewsToParent(parent, iconIds, selectedId, false);
    }

    private void loadCustomIcons(final LinearLayout parent, int selectedId) {
        parent.removeAllViews();
        List<Short> iconIds = customIconManager.getCustomIconIds();
        addIconViewsToParent(parent, iconIds, selectedId, true);
    }

    private void addIconViewsToParent(LinearLayout parent, List<? extends Number> ids, int selectedId, boolean isCustom) {
        int size = (int)UnitUtils.dpToPx(40);
        int margin = (int)UnitUtils.dpToPx(2);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(margin, 0, margin, 0);

        for (Number idObj : ids) {
            final short id = idObj.shortValue();
            ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(params);
            imageView.setPadding(4, 4, 4, 4);
            imageView.setBackgroundResource(R.drawable.icon_background);
            imageView.setTag(id);
            imageView.setSelected(id == selectedId);

            if (isCustom) imageView.setImageBitmap(customIconManager.loadIcon(id));
            else {
                try (InputStream is = getAssets().open("inputcontrols/icons/" + id + ".png")) {
                    imageView.setImageBitmap(BitmapFactory.decodeStream(is));
                } catch (IOException e) {}
            }

            imageView.setOnClickListener(v -> {
                // Deselect others in BOTH lists
                View root = (View) parent.getParent().getParent().getParent();
                clearSelection((LinearLayout) root.findViewById(R.id.LLIconList));
                clearSelection((LinearLayout) root.findViewById(R.id.LLCustomIconList));
                v.setSelected(true);
            });
            parent.addView(imageView);
        }
    }

    private void clearSelection(LinearLayout layout) {
        if (layout == null) return;
        for (int i = 0; i < layout.getChildCount(); i++) layout.getChildAt(i).setSelected(false);
    }

    // --- REMAINDER OF YOUR SPINNER/BINDING LOGIC ---
    private void loadTypeSpinner(final ControlElement element, Spinner spinner, Runnable callback) {
        if (spinner == null) return;
        AccentArrayAdapter<String> adapter = new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, ControlElement.Type.names());
        adapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(element.getType().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setType(ControlElement.Type.values()[position]);
                profile.save();
                callback.run();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadShapeSpinner(final ControlElement element, Spinner spinner) {
        if (spinner == null) return;
        AccentArrayAdapter<String> adapter = new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, ControlElement.Shape.names());
        adapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(element.getShape().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setShape(ControlElement.Shape.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadRangeSpinner(final ControlElement element, Spinner spinner) {
        if (spinner == null) return;
        AccentArrayAdapter<String> adapter = new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, ControlElement.Range.names());
        adapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(element.getRange().ordinal(), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                element.setRange(ControlElement.Range.values()[position]);
                profile.save();
                inputControlsView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBindingSpinners(ControlElement element, View view) {
        LinearLayout container = view.findViewById(R.id.LLBindings);
        if (container == null) return;
        container.removeAllViews();
        ControlElement.Type type = element.getType();
        if (type == ControlElement.Type.BUTTON) loadBindingSpinner(element, container, 0, R.string.binding);
        else if (type == ControlElement.Type.D_PAD || type == ControlElement.Type.STICK || type == ControlElement.Type.TRACKPAD || type == ControlElement.Type.DYNAMIC_STICK) {
            loadBindingSpinner(element, container, 0, R.string.binding_up);
            loadBindingSpinner(element, container, 1, R.string.binding_right);
            loadBindingSpinner(element, container, 2, R.string.binding_down);
            loadBindingSpinner(element, container, 3, R.string.binding_left);
        }
        else if (type == ControlElement.Type.BUTTON_GRID) {
            int rows = getGridRowsForEditor(element);
            int cols = getGridColsForEditor(element);
            int total = rows * cols;
            for (int i = 0; i < total; i++) {
                int r = i / cols + 1;
                int c = i % cols + 1;
                String label = "R" + r + "C" + c;
                loadBindingSpinner(element, container, i, label);
            }
        }
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, String title) {
        View bindingView = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);
        ((TextView)bindingView.findViewById(R.id.TVTitle)).setText(title);
        wireBindingSpinner(element, bindingView, container, index);
    }

    private void loadBindingSpinner(final ControlElement element, LinearLayout container, final int index, int titleResId) {
        View bindingView = LayoutInflater.from(this).inflate(R.layout.binding_field, container, false);
        ((TextView)bindingView.findViewById(R.id.TVTitle)).setText(titleResId);
        wireBindingSpinner(element, bindingView, container, index);
    }

    private void wireBindingSpinner(final ControlElement element, View view, LinearLayout container, final int index) {
        final Spinner sBindingType = view.findViewById(R.id.SBindingType);
        final Spinner sBinding = view.findViewById(R.id.SBinding);

        // Set the binding-type adapter in code (was android:entries in XML) so it uses
        // our blue-text item layouts and stays readable on a black background.
        // AccentArrayAdapter routes binding_spinner_item's colorPrimary text to the
        // runtime theme accent (was static #0055FF baked at inflation).
        AccentArrayAdapter<CharSequence> typeAdapter = new AccentArrayAdapter<>(
                this, R.layout.binding_spinner_item, getResources().getTextArray(R.array.binding_type_entries));
        typeAdapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
        sBindingType.setAdapter(typeAdapter);

        Runnable update = () -> {
            String[] bindingEntries = null;
            switch (sBindingType.getSelectedItemPosition()) {
                case 0: bindingEntries = Binding.keyboardBindingLabels(); break;
                case 1: bindingEntries = Binding.mouseBindingLabels(); break;
                case 2: bindingEntries = Binding.gamepadBindingLabels(); break;
            }
            AccentArrayAdapter<String> bindingAdapter =
                    new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, bindingEntries);
            bindingAdapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
            sBinding.setAdapter(bindingAdapter);
            AppUtils.setSpinnerSelectionFromValue(sBinding, element.getBindingAt(index).toString());
        };

        sBindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { update.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Binding selectedBinding = element.getBindingAt(index);
        if (selectedBinding.isKeyboard()) sBindingType.setSelection(0, false);
        else if (selectedBinding.isMouse()) sBindingType.setSelection(1, false);
        else if (selectedBinding.isGamepad()) sBindingType.setSelection(2, false);

        sBinding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Binding binding = Binding.NONE;
                switch (sBindingType.getSelectedItemPosition()) {
                    case 0: binding = Binding.keyboardBindingValues()[position]; break;
                    case 1: binding = Binding.mouseBindingValues()[position]; break;
                    case 2: binding = Binding.gamepadBindingValues()[position]; break;
                }
                if (binding != element.getBindingAt(index)) {
                    element.setBindingAt(index, binding);
                    profile.save();
                    inputControlsView.invalidate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        update.run();

        if (element.getType() == ControlElement.Type.BUTTON_GRID) {
            // Long-press on grid binding rows to set a key combo.
            view.setOnLongClickListener(v -> {
                showComboEditorDialog(element, index);
                return true;
            });
        }

        container.addView(view);
    }

    /** Show dialog to configure a multi-key combo for a binding slot */
    private void showComboEditorDialog(final ControlElement element, final int index) {
        // Get modifier keys (CTRL, SHIFT, ALT) and main key options
        final Binding[] modifierOptions = {
            Binding.KEY_CTRL_L, Binding.KEY_SHIFT_L, Binding.KEY_ALT_L
        };
        final String[] modifierLabels = {"CTRL", "SHIFT", "ALT"};
        final boolean[] selectedModifiers = new boolean[3];

        // Pre-fill from existing combo
        Binding[] existingCombo = element.getCombo(index);
        if (existingCombo != null) {
            for (Binding b : existingCombo) {
                for (int i = 0; i < modifierOptions.length; i++) {
                    if (b == modifierOptions[i]) selectedModifiers[i] = true;
                }
            }
        }

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 0);

        // Checkboxes for modifiers
        for (int i = 0; i < modifierOptions.length; i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(modifierLabels[i]);
            cb.setChecked(selectedModifiers[i]);
            final int idx = i;
            cb.setOnCheckedChangeListener((btn, checked) -> selectedModifiers[idx] = checked);
            ll.addView(cb);
        }

        // Spinner for main key
        final Spinner mainKeySpinner = new Spinner(this);
        final Binding[] mainKeyValues = Binding.keyboardBindingValues();
        String[] allKeyLabels = new String[mainKeyValues.length];
        for (int i = 0; i < mainKeyValues.length; i++) allKeyLabels[i] = mainKeyValues[i].toString();
        AccentArrayAdapter<String> keyAdapter = new AccentArrayAdapter<>(this, R.layout.binding_spinner_item, allKeyLabels);
        keyAdapter.setDropDownViewResource(R.layout.binding_spinner_dropdown_item);
        mainKeySpinner.setAdapter(keyAdapter);

        // Set current main key
        Binding currentMain = element.getBindingAt(index);
        if (existingCombo != null) {
            for (Binding b : existingCombo) {
                if (b.isKeyboard() && b != Binding.KEY_CTRL_L && b != Binding.KEY_SHIFT_L && b != Binding.KEY_ALT_L) {
                    currentMain = b;
                    break;
                }
            }
        }
        mainKeySpinner.setSelection(getBindingPosition(mainKeyValues, currentMain), false);

        TextView tvLabel = new TextView(this);
        tvLabel.setText("Main key:");
        tvLabel.setTextColor(0xFFAAAAAA);
        tvLabel.setPadding(0, 20, 0, 8);
        ll.addView(tvLabel);
        ll.addView(mainKeySpinner);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Key Combo — " + getBindingLabel(element, index))
            .setView(ll)
            .setPositiveButton("Save", (d, which) -> {
                // Build combo array
                List<Binding> comboList = new ArrayList<>();
                for (int i = 0; i < modifierOptions.length; i++) {
                    if (selectedModifiers[i]) comboList.add(modifierOptions[i]);
                }
                // Add main key
                int mainKeyPosition = mainKeySpinner.getSelectedItemPosition();
                Binding mainBinding = mainKeyPosition >= 0 && mainKeyPosition < mainKeyValues.length
                    ? mainKeyValues[mainKeyPosition]
                    : Binding.NONE;
                if (mainBinding != Binding.NONE) comboList.add(mainBinding);

                if (mainBinding != Binding.NONE && comboList.size() > 1) {
                    element.setCombo(index, comboList.toArray(new Binding[0]));
                    // Also set the primary binding to the main key for display
                    element.setBindingAt(index, mainBinding);
                } else {
                    element.setCombo(index, null); // clear combo
                    if (mainBinding != Binding.NONE) element.setBindingAt(index, mainBinding);
                }
                profile.save();
                inputControlsView.invalidate();
            })
            .setNegativeButton("Clear Combo", (d, which) -> {
                element.setCombo(index, null);
                profile.save();
                inputControlsView.invalidate();
            })
            .setNeutralButton("Cancel", null)
            .show();
    }

    private String getBindingLabel(ControlElement element, int index) {
        if (element.getType() == ControlElement.Type.BUTTON_GRID) {
            int cols = getGridColsForEditor(element);
            int r = index / cols + 1;
            int c = index % cols + 1;
            return "R" + r + "C" + c;
        }
        return "Binding " + (index + 1);
    }

    private int getBindingPosition(Binding[] values, Binding binding) {
        if (binding == null) binding = Binding.NONE;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == binding) return i;
        }
        return 0;
    }

    private int getGridRowsForEditor(ControlElement element) {
        return element.getGridRows() > 0 ? element.getGridRows() : DEFAULT_GRID_ROWS;
    }

    private int getGridColsForEditor(ControlElement element) {
        return element.getGridCols() > 0 ? element.getGridCols() : DEFAULT_GRID_COLS;
    }

    private int getGridCellCountForEditor(ControlElement element) {
        return getGridRowsForEditor(element) * getGridColsForEditor(element);
    }

    private void prepareGridForFill(ControlElement element) {
        int total = getGridCellCountForEditor(element);
        if (element.getBindingCount() != total) element.setBindingCount(total);
    }

    private void clearGridCell(ControlElement element, int index) {
        element.setBindingAt(index, Binding.NONE);
        element.setCombo(index, null);
    }

    /** Add a small quick-fill button to a linear layout */
    private void addQuickFillButton(LinearLayout parent, String label, Runnable action) {
        android.widget.Button btn = new android.widget.Button(this);
        btn.setText(label);
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setPadding(12, 4, 12, 4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        parent.addView(btn);
    }

    /** Fill grid with QWERTY row: A S D F ... (wraps) */
    private void fillGridQWERTY(ControlElement element) {
        Binding[] keys = {
            Binding.KEY_Q, Binding.KEY_W, Binding.KEY_E, Binding.KEY_R, Binding.KEY_T, Binding.KEY_Y, Binding.KEY_U, Binding.KEY_I, Binding.KEY_O, Binding.KEY_P,
            Binding.KEY_A, Binding.KEY_S, Binding.KEY_D, Binding.KEY_F, Binding.KEY_G, Binding.KEY_H, Binding.KEY_J, Binding.KEY_K, Binding.KEY_L,
            Binding.KEY_Z, Binding.KEY_X, Binding.KEY_C, Binding.KEY_V, Binding.KEY_B, Binding.KEY_N, Binding.KEY_M
        };
        prepareGridForFill(element);
        int total = getGridCellCountForEditor(element);
        for (int i = 0; i < total; i++) {
            element.setBindingAt(i, i < keys.length ? keys[i] : Binding.NONE);
            element.setCombo(i, null);
        }
        profile.save();
        inputControlsView.invalidate();
    }

    /** Fill grid with F1-F12 keys */
    private void fillGridFKeys(ControlElement element) {
        Binding[] keys = {
            Binding.KEY_F1, Binding.KEY_F2, Binding.KEY_F3, Binding.KEY_F4,
            Binding.KEY_F5, Binding.KEY_F6, Binding.KEY_F7, Binding.KEY_F8,
            Binding.KEY_F9, Binding.KEY_F10, Binding.KEY_F11, Binding.KEY_F12
        };
        prepareGridForFill(element);
        int total = getGridCellCountForEditor(element);
        for (int i = 0; i < total; i++) {
            element.setBindingAt(i, i < keys.length ? keys[i] : Binding.NONE);
            element.setCombo(i, null);
        }
        profile.save();
        inputControlsView.invalidate();
    }

    /** Fill grid with NumPad layout: 7 8 9 / 4 5 6 * / 1 2 3 - / 0 . + Enter */
    private void fillGridNumPad(ControlElement element) {
        Binding[] keys = {
            Binding.KEY_KP_7, Binding.KEY_KP_8, Binding.KEY_KP_9, Binding.KEY_KP_ADD,
            Binding.KEY_KP_4, Binding.KEY_KP_5, Binding.KEY_KP_6, Binding.KEY_MINUS,
            Binding.KEY_KP_1, Binding.KEY_KP_2, Binding.KEY_KP_3, Binding.KEY_ENTER,
            Binding.KEY_KP_0, Binding.KEY_PERIOD, Binding.KEY_BKSP, Binding.KEY_ESC
        };
        prepareGridForFill(element);
        int total = getGridCellCountForEditor(element);
        for (int i = 0; i < total; i++) {
            element.setBindingAt(i, i < keys.length ? keys[i] : Binding.NONE);
            element.setCombo(i, null);
        }
        profile.save();
        inputControlsView.invalidate();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);
    }
}
