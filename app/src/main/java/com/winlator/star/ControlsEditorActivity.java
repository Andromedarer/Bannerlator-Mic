package com.winlator.star;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.graphics.Rect;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.ComposeView;

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

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

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

    private View sidebarOverlay;
    private View sidebarScrollView;
    private LinearLayout sidebarContent;
    private View sidebarSettingsView;
    private ControlElement sidebarEditingElement;
    private boolean sidebarOpen = false;
    private boolean sidebarOnRight = false;
    private android.app.AlertDialog activeControlTypeDialog;

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
        inputControlsView.setProfile(profile);

        ComposeView composeToolbar = findViewById(R.id.ComposeToolbar);
        if (composeToolbar != null) {
            composeToolbar.setContent(new Function2<Composer, Integer, Unit>() {
                @Override
                public Unit invoke(Composer composer, Integer changed) {
                    ControlsEditorToolbarKt.ControlsEditorToolbar(
                        profile.getName(),
                        new Function0<Unit>() {
                            @Override
                            public Unit invoke() {
                                showAddElementTypeDialog();
                                return Unit.INSTANCE;
                            }
                        },
                        new Function0<Unit>() {
                            @Override
                            public Unit invoke() {
                                removeElement();
                                return Unit.INSTANCE;
                            }
                        },
                        new Function0<Unit>() {
                            @Override
                            public Unit invoke() {
                                ControlElement selectedElement = inputControlsView.getSelectedElement();
                                if (selectedElement != null) showControlElementSettings(findViewById(R.id.BTElementSettings));
                                else AppUtils.showToast(ControlsEditorActivity.this, R.string.no_control_element_selected);
                                return Unit.INSTANCE;
                            }
                        },
                        new Function0<Unit>() {
                            @Override
                            public Unit invoke() {
                                showBackgroundImageDialog();
                                return Unit.INSTANCE;
                            }
                        },
                        composer,
                        0
                    );
                    return Unit.INSTANCE;
                }
            });
        }

        FrameLayout container = findViewById(R.id.FLContainer);
        container.addView(inputControlsView, 0);

        sidebarOverlay = findViewById(R.id.VSidebarOverlay);
        sidebarScrollView = findViewById(R.id.SVSidebar);
        sidebarContent = findViewById(R.id.LLSidebarContent);
        if (sidebarOverlay != null) {
            sidebarOverlay.setOnClickListener(v -> closeSidebar());
        }

        View btAddElement = container.findViewById(R.id.BTAddElement);
        if (btAddElement != null) btAddElement.setOnClickListener(this);
        View btRemoveElement = container.findViewById(R.id.BTRemoveElement);
        if (btRemoveElement != null) btRemoveElement.setOnClickListener(this);
        View btElementSettings = container.findViewById(R.id.BTElementSettings);
        if (btElementSettings != null) btElementSettings.setOnClickListener(this);

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
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                AppUtils.showToast(this, R.string.unable_to_load_image);
                return;
            }

            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap != null) {
                inputControlsView.setBackgroundImage(bitmap);
                AppUtils.showToast(this, R.string.background_image_set);
            } else {
                AppUtils.showToast(this, R.string.unable_to_load_image);
            }
        } catch (IOException e) {
            AppUtils.showToast(this, R.string.unable_to_load_image);
        }
    }

    private void showAddElementTypeDialog() {
        showAddElementPicker();
    }

    private void showAddElementPicker() {
        dismissActiveControlTypeDialog();
        View pickerView = LayoutInflater.from(this).inflate(R.layout.control_type_picker, null, false);
        GridLayout grid = pickerView.findViewById(R.id.GLControlTypePicker);
        if (grid == null) return;

        final ControlElement.Type[] types = {
            ControlElement.Type.BUTTON,
            ControlElement.Type.D_PAD,
            ControlElement.Type.RANGE_BUTTON,
            ControlElement.Type.STICK,
            ControlElement.Type.TRACKPAD,
            ControlElement.Type.DYNAMIC_STICK,
            ControlElement.Type.MOUSE_AREA,
            ControlElement.Type.BUTTON_GRID
        };
        final int[] icons = {
            R.drawable.icon_keyboard,
            R.drawable.icon_gamepad,
            R.drawable.icon_screen_effect,
            R.drawable.icon_gamepad,
            R.drawable.icon_mouse,
            R.drawable.icon_gamepad,
            R.drawable.icon_mouse,
            R.drawable.icon_palette
        };

        grid.removeAllViews();
        for (int i = 0; i < types.length; i++) {
            final ControlElement.Type type = types[i];
            grid.addView(createControlTypePickerItem(grid, type, icons[i]));
        }

        activeControlTypeDialog = new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_control_type)
            .setView(pickerView)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        activeControlTypeDialog.show();
    }

    private View createControlTypePickerItem(GridLayout parent, ControlElement.Type type, int iconResId) {
        int cellMargin = (int) UnitUtils.dpToPx(6);
        int cellPadding = (int) UnitUtils.dpToPx(12);
        int iconSize = (int) UnitUtils.dpToPx(36);

        LinearLayout cell = new LinearLayout(this);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(cellMargin, cellMargin, cellMargin, cellMargin);
        cell.setLayoutParams(params);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(cellPadding, cellPadding, cellPadding, cellPadding);
        cell.setClickable(true);
        cell.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1F2937);
        background.setCornerRadius(UnitUtils.dpToPx(12));
        background.setStroke((int) UnitUtils.dpToPx(1), 0xFF334155);
        cell.setBackground(background);

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        icon.setLayoutParams(iconLp);
        icon.setImageResource(iconResId);
        if (type == ControlElement.Type.DYNAMIC_STICK) {
            icon.setColorFilter(0xFF8B5CF6, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        cell.addView(icon);

        TextView label = new TextView(this);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = (int) UnitUtils.dpToPx(8);
        label.setLayoutParams(labelLp);
        label.setText(getControlTypeLabel(type));
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13f);
        label.setGravity(Gravity.CENTER);
        cell.addView(label);

        cell.setOnClickListener(v -> {
            if (inputControlsView.addElement(type)) {
                ControlElement selectedElement = inputControlsView.getSelectedElement();
                dismissActiveControlTypeDialog();
                if (selectedElement != null) showControlElementSettings(findViewById(R.id.BTElementSettings));
            } else {
                AppUtils.showToast(this, R.string.no_profile_selected);
            }
        });

        return cell;
    }

    private String getControlTypeLabel(ControlElement.Type type) {
        switch (type) {
            case BUTTON: return getString(R.string.control_type_button);
            case D_PAD: return getString(R.string.control_type_d_pad);
            case RANGE_BUTTON: return getString(R.string.control_type_range_button);
            case STICK: return getString(R.string.control_type_stick);
            case TRACKPAD: return getString(R.string.control_type_trackpad);
            case DYNAMIC_STICK: return getString(R.string.control_type_dynamic_stick);
            case MOUSE_AREA: return getString(R.string.control_type_mouse_area);
            case BUTTON_GRID: return getString(R.string.control_type_button_grid);
            default: return type.name().replace('_', ' ');
        }
    }

    private void showBackgroundImageDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.set_background_image)
            .setItems(new CharSequence[]{getString(R.string.pick_from_files), getString(R.string.clear_background)}, (d, which) -> {
                if (which == 0) {
                    new android.app.AlertDialog.Builder(this)
                        .setItems(new CharSequence[]{getString(R.string.browse_files), getString(R.string.pick_via_system)}, (d2, which2) -> {
                            if (which2 == 0) {
                                Intent intent = new Intent(this, FilePickerActivity.class);
                                intent.putExtra(FilePickerActivity.EXTRA_EXTENSIONS, new String[]{"png", "jpg", "jpeg", "webp", "bmp"});
                                intent.putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, getString(R.string.select_background_image));
                                bgImagePickerInAppLauncher.launch(intent);
                            } else {
                                bgImagePickerLauncher.launch("image/*");
                            }
                        })
                        .show();
                } else {
                    inputControlsView.setBackgroundImage(null);
                    AppUtils.showToast(this, R.string.background_cleared);
                }
            })
            .show();
    }

    // Shared: add a custom icon from any Uri (file:// from the in-app picker, content:// from SAF).
    private void addCustomIconFromUri(Uri uri) {
        ControlElement selectedElement = inputControlsView.getSelectedElement();
        if (currentLLCustomIconList == null || selectedElement == null) return;

        customIconManager.addCustomIcon(uri);
        loadCustomIcons(currentLLCustomIconList, selectedElement.getIconId());
    }

    // Two-option chooser: built-in picker first, then system SAF.
    public void promptPickCustomIcon() {
        new android.app.AlertDialog.Builder(this)
            .setItems(new CharSequence[]{getString(R.string.browse_files), getString(R.string.pick_via_system)}, (d, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(this, FilePickerActivity.class);
                    intent.putExtra(FilePickerActivity.EXTRA_EXTENSIONS, new String[]{"png", "jpg", "jpeg", "webp", "bmp", "gif"});
                    intent.putExtra(FilePickerActivity.EXTRA_PICKER_TITLE, getString(R.string.select_icon_image));
                    iconPickerInAppLauncher.launch(intent);
                } else {
                    iconPickerLauncher.launch("image/*");
                }
            })
            .show();
    }

    private void dismissActiveControlTypeDialog() {
        if (activeControlTypeDialog != null) {
            activeControlTypeDialog.dismiss();
            activeControlTypeDialog = null;
        }
    }

    private void removeElement() {
        if (!inputControlsView.removeElement()) AppUtils.showToast(this, R.string.no_control_element_selected);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.BTAddElement:
                showAddElementTypeDialog();
                break;
            case R.id.BTRemoveElement:
                removeElement();
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
        ll.setPadding((int) UnitUtils.dpToPx(24), (int) UnitUtils.dpToPx(16), (int) UnitUtils.dpToPx(24), 0);

        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);
        seekBar.setProgress((int)(inputControlsView.getBackgroundOpacity() * 100));
        ll.addView(seekBar);

        final TextView tv = new TextView(this);
        tv.setText(getString(R.string.opacity_percent, (int)(inputControlsView.getBackgroundOpacity() * 100)));
        ll.addView(tv);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float op = progress / 100f;
                inputControlsView.setBackgroundOpacity(op);
                tv.setText(getString(R.string.opacity_percent, progress));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.background_opacity)
            .setView(ll)
            .setPositiveButton(R.string.ok, null)
            .show();
    }

    private void showControlElementSettings(View anchorView) {
        final ControlElement element = inputControlsView.getSelectedElement();
        if (element == null || sidebarContent == null || sidebarScrollView == null || sidebarOverlay == null) return;
        if (sidebarOpen) saveSidebarState();

        ComposeView composeView = new ComposeView(this);
        composeView.setContent(new Function2<Composer, Integer, Unit>() {
            @Override
            public Unit invoke(Composer composer, Integer changed) {
                ControlsEditorSettingsPaneKt.ControlsEditorSettingsPane(
                    element,
                    profile,
                    new Function0<Unit>() {
                        @Override
                        public Unit invoke() {
                            inputControlsView.invalidate();
                            return Unit.INSTANCE;
                        }
                    },
                    customIconManager,
                    ControlsEditorActivity.this,
                    composer,
                    0
                );
                return Unit.INSTANCE;
            }
        });

        sidebarContent.removeAllViews();
        sidebarContent.addView(composeView);
        sidebarSettingsView = composeView;
        sidebarEditingElement = element;

        final float sidebarWidthPx = UnitUtils.dpToPx(300);
        final float screenWidth = getResources().getDisplayMetrics().widthPixels;
        Rect elementBounds = element.getBoundingBox();
        final float centerX = elementBounds != null && !elementBounds.isEmpty() ? elementBounds.centerX() : element.getX();
        sidebarOnRight = centerX <= screenWidth / 2f;

        boolean animateIn = !sidebarOpen || sidebarScrollView == null || sidebarScrollView.getVisibility() != View.VISIBLE;

        if (sidebarScrollView != null) {
            if (sidebarScrollView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sidebarScrollView.getLayoutParams();
                lp.gravity = sidebarOnRight ? (Gravity.END | Gravity.TOP) : (Gravity.START | Gravity.TOP);
                sidebarScrollView.setLayoutParams(lp);
            }
            sidebarScrollView.animate().cancel();
            sidebarScrollView.setVisibility(View.VISIBLE);
            sidebarScrollView.setAlpha(1f);
            if (animateIn) {
                sidebarScrollView.setTranslationX(sidebarOnRight ? sidebarWidthPx : -sidebarWidthPx);
                sidebarScrollView.animate()
                    .translationX(0f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            } else {
                sidebarScrollView.setTranslationX(0f);
            }
        }

        if (sidebarOverlay != null) {
            sidebarOverlay.animate().cancel();
            sidebarOverlay.setVisibility(View.VISIBLE);
            if (animateIn) {
                sidebarOverlay.setAlpha(0f);
                sidebarOverlay.animate().alpha(1f).setDuration(200).start();
            } else {
                sidebarOverlay.setAlpha(1f);
            }
        }

        sidebarOpen = true;
    }

    private void saveSidebarState() {
        profile.save();
    }

    private void closeSidebar() {
        if (sidebarScrollView == null || sidebarOverlay == null) return;
        if (sidebarScrollView.getVisibility() != View.VISIBLE) return;

        saveSidebarState();
        sidebarOpen = false;

        final float sidebarWidthPx = UnitUtils.dpToPx(300);
        sidebarOverlay.animate().cancel();
        sidebarScrollView.animate().cancel();

        sidebarOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .start();

        sidebarScrollView.animate()
            .translationX(sidebarOnRight ? sidebarWidthPx : -sidebarWidthPx)
            .setDuration(250)
            .setInterpolator(new DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    sidebarScrollView.setVisibility(View.GONE);
                    sidebarOverlay.setVisibility(View.GONE);
                    sidebarScrollView.setAlpha(1f);
                    sidebarOverlay.setAlpha(1f);
                    sidebarSettingsView = null;
                    sidebarEditingElement = null;
                    sidebarScrollView.animate().setListener(null);
                }
            })
            .start();
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
            if (filenames != null) {
                for (String file : filenames) {
                    try {
                        iconIds.add(Byte.parseByte(FileUtils.getBasename(file)));
                    } catch (NumberFormatException ignored) {}
                }
            }
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
                clearIconSelections();
                v.setSelected(true);
            });
            parent.addView(imageView);
        }
    }

    private void clearIconSelections() {
        if (sidebarSettingsView == null) return;
        clearSelection(sidebarSettingsView.findViewById(R.id.LLIconList));
        clearSelection(sidebarSettingsView.findViewById(R.id.LLCustomIconList));
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
                String label = getString(R.string.binding_grid_cell_label, r, c);
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
        final String[] modifierLabels = {
            getString(R.string.ctrl), getString(R.string.shift), getString(R.string.alt)
        };
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
        tvLabel.setText(R.string.main_key);
        tvLabel.setTextColor(0xFFAAAAAA);
        tvLabel.setPadding(0, 20, 0, 8);
        ll.addView(tvLabel);
        ll.addView(mainKeySpinner);

        new android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.key_combo_title, getBindingLabel(element, index)))
            .setView(ll)
            .setPositiveButton(R.string.save, (d, which) -> {
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
            .setNegativeButton(R.string.clear_combo, (d, which) -> {
                element.setCombo(index, null);
                profile.save();
                inputControlsView.invalidate();
            })
            .setNeutralButton(R.string.cancel, null)
            .show();
    }

    private String getBindingLabel(ControlElement element, int index) {
        if (element.getType() == ControlElement.Type.BUTTON_GRID) {
            int cols = getGridColsForEditor(element);
            int r = index / cols + 1;
            int c = index % cols + 1;
            return getString(R.string.binding_grid_cell_label, r, c);
        }
        return getString(R.string.binding_slot_label, index + 1);
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

    void prepareGridForFill(ControlElement element) {
        int total = getGridCellCountForEditor(element);
        if (element.getBindingCount() != total) element.setBindingCount(total);
    }

    void clearGridCell(ControlElement element, int index) {
        element.setBindingAt(index, Binding.NONE);
        element.setCombo(index, null);
    }

    /** Add a small quick-fill button to a linear layout */
    private void addQuickFillButton(LinearLayout parent, int labelResId, Runnable action) {
        android.widget.Button btn = new android.widget.Button(this);
        btn.setText(labelResId);
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
    void fillGridQWERTY(ControlElement element) {
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
    void fillGridFKeys(ControlElement element) {
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
    void fillGridNumPad(ControlElement element) {
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
        if (sidebarScrollView != null && sidebarScrollView.getVisibility() == View.VISIBLE) {
            closeSidebar();
            return;
        }
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_down, R.anim.slide_out_up);
    }

    @Override
    protected void onDestroy() {
        dismissActiveControlTypeDialog();
        super.onDestroy();
    }
}
