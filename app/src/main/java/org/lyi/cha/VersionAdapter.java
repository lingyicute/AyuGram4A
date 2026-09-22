package org.lyi.cha;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.List;

public class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.VH> {

    public static class Item {
        public final String tag;
        public final String date;
        public final boolean prerelease;
        public boolean installed;
        public boolean active;

        public Item(String tag, String date, boolean prerelease, boolean installed, boolean active) {
            this.tag = tag;
            this.date = date;
            this.prerelease = prerelease;
            this.installed = installed;
            this.active = active;
        }
    }

    public interface Callbacks {
        void onDownload(Item item);

        void onUse(Item item);

        void onDelete(Item item);
    }

    private final List<Item> items;
    private final Callbacks cb;

    public VersionAdapter(List<Item> items, Callbacks cb) {
        this.items = items;
        this.cb = cb;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_version, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item it = items.get(position);
        h.tag.setText(it.prerelease ? it.tag + "  (预发布)" : it.tag);
        h.date.setText(it.date.isEmpty() ? "发布日期未知" : "发布于 " + it.date);

        int color;
        if (it.active) {
            h.state.setText("● 当前使用");
            color = MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorPrimary);
        } else if (it.installed) {
            h.state.setText("● 已安装");
            color = MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorTertiary);
        } else {
            h.state.setText("○ 未安装");
            color = MaterialColors.getColor(h.itemView, com.google.android.material.R.attr.colorOutline);
        }
        h.state.setTextColor(color);

        if (!it.installed) {
            h.primary.setVisibility(View.VISIBLE);
            h.primary.setText("下载");
            h.primary.setOnClickListener(v -> cb.onDownload(it));
        } else if (!it.active) {
            h.primary.setVisibility(View.VISIBLE);
            h.primary.setText("使用");
            h.primary.setOnClickListener(v -> cb.onUse(it));
        } else {
            h.primary.setVisibility(View.GONE);
            h.primary.setOnClickListener(null);
        }
        h.delete.setVisibility(it.installed ? View.VISIBLE : View.GONE);
        h.delete.setOnClickListener(v -> cb.onDelete(it));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tag, date, state;
        final MaterialButton primary, delete;

        VH(@NonNull View v) {
            super(v);
            tag = v.findViewById(R.id.tvTag);
            date = v.findViewById(R.id.tvDate);
            state = v.findViewById(R.id.tvState);
            primary = v.findViewById(R.id.btnPrimary);
            delete = v.findViewById(R.id.btnDelete);
        }
    }
}
