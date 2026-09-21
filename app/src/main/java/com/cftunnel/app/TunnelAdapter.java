package com.cftunnel.app;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

public class TunnelAdapter extends RecyclerView.Adapter<TunnelAdapter.VH> {

    public interface Callbacks {
        void onToggle(Tunnel t, boolean on);

        void onMore(Tunnel t, View anchor);
    }

    private final List<Tunnel> items;
    private final Callbacks cb;

    public TunnelAdapter(List<Tunnel> items, Callbacks cb) {
        this.items = items;
        this.cb = cb;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tunnel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Tunnel t = items.get(position);
        boolean running = TunnelService.isRunning(t.id);

        h.name.setText(t.name);
        h.target.setText(t.hostname + "  →  " + t.localAddress());
        h.status.setText(running ? "运行中 · 监听 " + t.localAddress() : "已停止");

        int color = MaterialColors.getColor(h.itemView, running
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOutline);
        h.dot.setBackgroundTintList(ColorStateList.valueOf(color));
        h.status.setTextColor(color);

        h.sw.setOnCheckedChangeListener(null);
        h.sw.setChecked(running);
        h.sw.setOnCheckedChangeListener((b, on) -> cb.onToggle(t, on));
        h.more.setOnClickListener(v -> cb.onMore(t, v));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name, target, status;
        final View dot;
        final MaterialSwitch sw;
        final MaterialButton more;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            target = v.findViewById(R.id.tvTarget);
            status = v.findViewById(R.id.tvStatus);
            dot = v.findViewById(R.id.dot);
            sw = v.findViewById(R.id.swToggle);
            more = v.findViewById(R.id.btnMore);
        }
    }
}
