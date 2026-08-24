package com.example.dialsender.views;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.dialsender.R;

/**
 * Icon + label rows for AlertDialog pickers.
 *
 * The dial editor's pickers used to prefix their labels with emoji ("📷 Fondo",
 * "🔋 Estado") because a plain setItems() list has nowhere to put an icon. Those
 * rendered at a different size, weight and colour on every device. This adapter
 * gives the same lists real vectors that follow the theme.
 */
public class FoggChoiceAdapter extends ArrayAdapter<String> {

    private final int[] icons;

    /**
     * @param icons one drawable per label; pass 0 for a row with no icon. May be
     *              shorter than {@code labels} — the surplus rows just get none.
     */
    public FoggChoiceAdapter(@NonNull Context context, @NonNull String[] labels, @NonNull int[] icons) {
        super(context, R.layout.item_fogg_choice, R.id.txtChoiceLabel, labels);
        this.icons = icons;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View row = convertView != null ? convertView
                : LayoutInflater.from(getContext()).inflate(R.layout.item_fogg_choice, parent, false);

        TextView label = row.findViewById(R.id.txtChoiceLabel);
        label.setText(getItem(position));

        ImageView icon = row.findViewById(R.id.imgChoiceIcon);
        int res = position < icons.length ? icons[position] : 0;
        if (res != 0) {
            icon.setImageResource(res);
            icon.setVisibility(View.VISIBLE);
        } else {
            // Keep the slot so labels stay aligned whether or not a row has one.
            icon.setImageDrawable(null);
            icon.setVisibility(View.INVISIBLE);
        }
        return row;
    }
}
