package com.nexds.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_ROM = 1, REQ_BIOS = 2;
    private File romDir, sysDir;
    private final List<File> roms = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView empty, biosInfo;
    private Button coreBtn;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        romDir = new File(getFilesDir(), "roms"); romDir.mkdirs();
        sysDir = new File(getFilesDir(), "system"); sysDir.mkdirs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(20), dp(40), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("NexDS PRO");
        title.setTextSize(30);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Emulador Nintendo DS");
        sub.setTextColor(0xFF8FA3B5);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        Button open = new Button(this);
        open.setText("Abrir ROM (.nds)");
        open.setOnClickListener(v -> pick(REQ_ROM, false));
        root.addView(open);

        Button bios = new Button(this);
        bios.setText("Importar BIOS/firmware (opcional)");
        bios.setOnClickListener(v -> pick(REQ_BIOS, true));
        root.addView(bios);

        biosInfo = new TextView(this);
        biosInfo.setTextColor(0xFF8FA3B5);
        biosInfo.setTextSize(12);
        root.addView(biosInfo);

        coreBtn = new Button(this);
        coreBtn.setOnClickListener(v -> {
            int c = 1 - getSharedPreferences("cfg", 0).getInt("core", 0);
            getSharedPreferences("cfg", 0).edit().putInt("core", c).apply();
            refresh();
        });
        root.addView(coreBtn);

        TextView rec = new TextView(this);
        rec.setText("Jogos recentes");
        rec.setTextColor(0xFFFFFFFF);
        rec.setTextSize(18);
        rec.setPadding(0, dp(20), 0, dp(6));
        root.addView(rec);

        empty = new TextView(this);
        empty.setText("Nenhum jogo ainda. Toque em \"Abrir ROM\".");
        empty.setTextColor(0xFF8FA3B5);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(empty);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> startGame(roms.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            final File f = roms.get(pos);
            new AlertDialog.Builder(this).setTitle("Remover?").setMessage(f.getName())
                .setPositiveButton("Remover", (d, w) -> { f.delete(); refresh(); })
                .setNegativeButton("Cancelar", null).show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        roms.clear();
        File[] fs = romDir.listFiles();
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            for (File f : fs) if (f.isFile()) roms.add(f);
        }
        adapter.clear();
        for (File f : roms) adapter.add(f.getName());
        adapter.notifyDataSetChanged();
        empty.setVisibility(roms.isEmpty() ? TextView.VISIBLE : TextView.GONE);
        int c = getSharedPreferences("cfg", 0).getInt("core", 0);
        coreBtn.setText("Core: " + (c == 1 ? "DeSmuME" : "melonDS") + " (toque para trocar)");
        boolean b7 = new File(sysDir, "bios7.bin").exists();
        boolean b9 = new File(sysDir, "bios9.bin").exists();
        boolean fw = new File(sysDir, "firmware.bin").exists();
        biosInfo.setText("BIOS: bios7 " + (b7 ? "OK" : "-") + " | bios9 " + (b9 ? "OK" : "-")
            + " | firmware " + (fw ? "OK" : "-") + ((b7 && b9 && fw) ? "" : "  (usando FreeBIOS)"));
    }

    private void pick(int req, boolean multi) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        final boolean rom = req == REQ_ROM;
        Toast.makeText(this, rom ? "Copiando ROM..." : "Importando...", Toast.LENGTH_SHORT).show();
        final int[] bad = {0};
        final List<String> got = new ArrayList<>();
        new Thread(() -> {
            File last = null;
            try {
                for (Uri u : uris) {
                    File f = copy(u, rom ? romDir : sysDir);
                    if (rom) { last = f; continue; }
                    String nm = biosName(f.length());
                    if (nm == null) { f.delete(); bad[0]++; continue; }
                    File dest = new File(sysDir, nm);
                    if (!f.equals(dest)) { dest.delete(); f.renameTo(dest); }
                    got.add(nm);
                }
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Erro ao copiar: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            final File f = last;
            runOnUiThread(() -> {
                if (rom && f != null) startGame(f);
                else {
                    Toast.makeText(this, got.isEmpty()
                        ? "Nenhum arquivo reconhecido (esperado bios7, bios9 ou firmware)"
                        : "Importado: " + got + (bad[0] > 0 ? " | ignorados: " + bad[0] : ""), Toast.LENGTH_LONG).show();
                    refresh();
                }
            });
        }).start();
    }

    private File copy(Uri u, File dir) throws IOException {
        String name = "arquivo.bin";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.getString(ix) != null) name = c.getString(ix);
            }
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File out = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("nao foi possivel abrir");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private static String biosName(long n) {
        if (n == 16384) return "bios7.bin";
        if (n == 4096) return "bios9.bin";
        if (n == 131072 || n == 262144 || n == 524288) return "firmware.bin";
        return null;
    }

    private void startGame(File f) {
        f.setLastModified(System.currentTimeMillis());
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra("rom", f.getAbsolutePath());
        startActivity(i);
    }
}
