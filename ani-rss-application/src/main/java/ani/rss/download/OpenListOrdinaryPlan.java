package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.util.other.TorrentMetadata;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Strict source plan used to recover a transfer even when the provider reports failure. */
final class OpenListOrdinaryPlan {
    static List<OpenListTaskStore.CollectionFile> from(File torrent, Function<String, String> rename) throws Exception {
        if (!torrent.isFile() || torrent.length() == 0 || !torrent.getName().endsWith(".torrent")) {
            return List.of(); // A magnet without metadata cannot prove completeness.
        }
        var metadata = TorrentMetadata.from(torrent);
        String[] names = metadata.getFilenames();
        long[] sizes = metadata.getLengths();
        var entries = new ArrayList<OpenListTaskStore.CollectionFile>();
        int videos = 0;
        for (int i = 0; i < names.length; i++) {
            String name = names[i].replace('\\', '/');
            String base = name.substring(name.lastIndexOf('/') + 1);
            boolean video = FileUtils.isVideoFormat(base);
            if (!video && !FileUtils.isSubtitleFormat(base)) continue;
            if (video) videos++;
            if (sizes[i] <= 0 || name.startsWith("/") || List.of(name.split("/")).contains("..")) {
                throw new IllegalArgumentException("OpenList 种子文件计划无效: " + name);
            }
            entries.add(new OpenListTaskStore.CollectionFile().setSource(name)
                    .setTarget(rename.apply(base)).setLength(sizes[i]));
        }
        if (videos != 1) throw new IllegalArgumentException("普通 OpenList 下载需要一个视频文件，多集种子请使用合集下载");
        OpenListCollectionOrganizer.validate(entries);
        return entries;
    }

    static boolean ready(OpenListTaskStore.Task task, List<ani.rss.entity.OpenListFileInfo> staged) {
        var entries = OpenListCollectionOrganizer.entries(task);
        if (entries.isEmpty()) return false;
        if (task.isCollectionPlanned()) return true; // The organizer verifies partial moves and renames.
        return entries.stream().allMatch(entry -> staged.stream().filter(file -> {
            String path = file.getPath() + "/" + file.getName();
            String prefix = task.getStagingPath() + "/";
            if (!path.startsWith(prefix)) return false;
            String relative = path.substring(prefix.length());
            return Boolean.FALSE.equals(file.getIsDir()) && Objects.equals(file.getSize(), entry.getLength())
                    && (relative.equals(entry.getSource()) || relative.endsWith("/" + entry.getSource()));
        }).count() == 1);
    }
}
