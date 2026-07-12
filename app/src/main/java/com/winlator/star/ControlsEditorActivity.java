package com.winlator.star;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.UnitUtils;
import com.winlator.star.widget.InputControlsView;

import java.io.IOException;
import java.io.InputStream;

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

    // Background image picker
    private ActivityResultLauncher<String> bgImagePickerLauncher;
    private ActivityResultLauncher<Intent> bgImagePickerInAppLauncher;

    private View sidebarOverlay;
    private View sidebarScrollView;
    private LinearLayout sidebarContent;
    private ComposeView sidebarComposeView;
    private boolean sidebarOpen = false;
    private boolean sidebarOnRight = false;
    private int sidebarSettingsReloadKey = 0;
    private android.app.AlertDialog activeControlTypeDialog;
    private android.app.AlertDialog activeGroupListDialog;

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
                                toggleGroupListDialog();
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
            sidebarOverlay.setClickable(false);
            sidebarOverlay.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    ControlElement hitElement = findElementAtScreen(event.getRawX(), event.getRawY());
                    if (hitElement != null) {
                        inputControlsView.selectElementAt(hitElement);
                        showControlElementSettings(v);
                        return true;
                    }
                    closeSidebar();
                    return true;
                }
                return false;
            });
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
                refreshSidebarSettings();
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

    private void toggleGroupListDialog() {
        if (activeGroupListDialog != null && activeGroupListDialog.isShowing()) {
            activeGroupListDialog.dismiss();
            activeGroupListDialog = null;
        }
        else {
            showGroupListDialog();
        }
    }

    private void showGroupListDialog() {
        if (profile == null) return;
        if (activeGroupListDialog != null) {
            activeGroupListDialog.dismiss();
            activeGroupListDialog = null;
        }

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int) UnitUtils.dpToPx(16), (int) UnitUtils.dpToPx(12), (int) UnitUtils.dpToPx(16), (int) UnitUtils.dpToPx(12));
        container.setBackgroundColor(0xFF1A1A2E);
        scrollView.addView(container);

        if (profile.getGroups().isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(getString(R.string.no_groups));
            emptyView.setTextColor(Color.WHITE);
            container.addView(emptyView);
        }
        else {
            for (ControlsProfile.GroupInfo group : profile.getGroups().values()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, (int) UnitUtils.dpToPx(8), 0, (int) UnitUtils.dpToPx(8));

                TextView nameView = new TextView(this);
                nameView.setText(group.getName());
                nameView.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(nameView, nameLp);

                TextView countView = new TextView(this);
                countView.setText(getString(R.string.group_element_count, profile.getGroupElementCount(group.getName())));
                countView.setTextColor(Color.WHITE);
                countView.setPadding(0, 0, (int) UnitUtils.dpToPx(12), 0);
                row.addView(countView);

                ImageButton visibilityButton = new ImageButton(this);
                visibilityButton.setBackgroundColor(Color.TRANSPARENT);
                visibilityButton.setImageResource(group.isVisible() ? R.drawable.icon_eye : R.drawable.icon_eye_off);
                visibilityButton.setContentDescription(getString(R.string.group_visibility));
                visibilityButton.setOnClickListener(v -> {
                    profile.setGroupVisible(group.getName(), !group.isVisible());
                    profile.save();
                    inputControlsView.invalidate();
                    refreshSidebarSettings();
                    showGroupListDialog();
                });
                row.addView(visibilityButton, new LinearLayout.LayoutParams((int) UnitUtils.dpToPx(40), (int) UnitUtils.dpToPx(40)));

                container.addView(row);
            }
        }

        activeGroupListDialog = new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.group_visibility)
            .setView(scrollView)
            .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss())
            .show();
        activeGroupListDialog.setOnDismissListener(dialog -> activeGroupListDialog = null);
    }

    // Shared: add a custom icon from any Uri (file:// from the in-app picker, content:// from SAF).
    private void addCustomIconFromUri(Uri uri) {
        ControlElement selectedElement = inputControlsView.getSelectedElement();
        if (selectedElement == null) return;

        customIconManager.addCustomIcon(uri);
        refreshSidebarSettings();
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
        if (inputControlsView.removeElement()) {
            closeSidebar();
        } else {
            AppUtils.showToast(this, R.string.no_control_element_selected);
        }
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

        sidebarComposeView = ensureSidebarComposeView();
        bindSidebarSettings(element);

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

    private ComposeView ensureSidebarComposeView() {
        if (sidebarComposeView == null) {
            sidebarComposeView = new ComposeView(this);
        }
        if (sidebarComposeView.getParent() == null && sidebarContent != null) {
            sidebarContent.addView(sidebarComposeView);
        }
        return sidebarComposeView;
    }

    private void bindSidebarSettings(final ControlElement element) {
        if (sidebarComposeView == null) return;
        final int reloadKey = sidebarSettingsReloadKey;
        sidebarComposeView.setContent(new Function2<Composer, Integer, Unit>() {
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
                    reloadKey,
                    ControlsEditorActivity.this,
                    composer,
                    0
                );
                return Unit.INSTANCE;
            }
        });
    }

    private void refreshSidebarSettings() {
        if (sidebarScrollView == null || sidebarScrollView.getVisibility() != View.VISIBLE) return;
        ControlElement selectedElement = inputControlsView.getSelectedElement();
        if (selectedElement == null) return;
        sidebarSettingsReloadKey++;
        ensureSidebarComposeView();
        bindSidebarSettings(selectedElement);
    }
    private void saveSidebarState() {
        profile.save();
    }

    /** Find a control element at the given raw screen coordinates (for overlay passthrough). */
    private ControlElement findElementAtScreen(float rawX, float rawY) {
        if (inputControlsView == null || profile == null || !profile.isElementsLoaded()) return null;
        // Convert raw screen coordinates to local coordinates relative to the InputControlsView
        int[] loc = new int[2];
        inputControlsView.getLocationOnScreen(loc);
        float localX = rawX - loc[0];
        float localY = rawY - loc[1];
        for (ControlElement element : profile.getElements()) {
            if (element.containsPoint(localX, localY)) return element;
        }
        return null;
    }

    public void closeSidebar() {
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
                    sidebarScrollView.animate().setListener(null);
                }
            })
            .start();
    }


    private int getGridRowsForEditor(ControlElement element) {
        return Math.max(1, element.getGridRows());
    }

    private int getGridColsForEditor(ControlElement element) {
        return Math.max(1, element.getGridCols());
    }

    private int getGridCellCountForEditor(ControlElement element) {
        return getGridRowsForEditor(element) * getGridColsForEditor(element);
    }

    void prepareGridForFill(ControlElement element) {
        int total = getGridCellCountForEditor(element);
        if (element.getBindingCount() != total) element.setBindingCount(total);
    }

    void clearGridCell(ControlElement element, int index) {
        if (index < 0 || index >= element.getBindingCount()) return;
        element.setBindingAt(index, Binding.NONE);
        element.setCombo(index, null);
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
        refreshSidebarSettings();
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
        refreshSidebarSettings();
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
        refreshSidebarSettings();
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
        if (activeGroupListDialog != null) {
            activeGroupListDialog.dismiss();
            activeGroupListDialog = null;
        }
        super.onDestroy();
    }
}
