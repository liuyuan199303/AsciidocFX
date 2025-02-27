package com.kodedu.service.ui.impl;

import com.kodedu.controller.ApplicationController;
import com.kodedu.helper.IOHelper;
import com.kodedu.other.Current;
import com.kodedu.other.Item;
import com.kodedu.service.DirectoryService;
import com.kodedu.service.EventService;
import com.kodedu.service.FileWatchService;
import com.kodedu.service.PathOrderService;
import com.kodedu.service.ThreadService;
import com.kodedu.service.ui.AwesomeService;
import com.kodedu.service.ui.FileBrowseService;
import com.kodedu.service.ui.PathItem;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.PostConstruct;

import static com.kodedu.helper.IOHelper.isHidden;

/**
 * Created by usta on 12.07.2014.
 */
@Component(FileBrowseService.label)
public class FileBrowseServiceImpl implements FileBrowseService {

    private Logger logger = LoggerFactory.getLogger(FileBrowseService.class);

    private final Current current;
    @Autowired
    private ApplicationController controller;
    @Autowired
    private EventService eventService;
    @Autowired
    private ThreadService threadService;
    @Autowired
    private AwesomeService awesomeService;
    @Autowired
    private FileWatchService fileWatchService;
    @Autowired
    private PathOrderService pathOrderService;

    private final Map<Path, TreeItem<Item>> directoryItemMap = new ConcurrentHashMap<Path, TreeItem<Item>>(); // only changed parent path or root path
    private final Map<Path, TreeItem<Item>> pathItemMap = new ConcurrentHashMap<Path, TreeItem<Item>>(); // parents + child paths
    private final Map<Path, Boolean> expandedPaths = new ConcurrentHashMap<Path, Boolean>(); // parents + child paths
    private Set<Path> lastSelectedItems = new HashSet<>();
    private PathItem<Item> rootItem;
    private TreeView<Item> treeView;
    private Path browsedPath;
    private ChangeListener<TreeItem<Item>> treeItemChangeListener;

    @Autowired
    public FileBrowseServiceImpl(Current current) {
        this.current = current;
    }

    @PostConstruct
    public void install_listeners() {
        // Listen to working directory update events
        eventService.subscribe(DirectoryService.WORKING_DIRECTORY_UPDATE_EVENT, event -> {
            Path path = (Path) event.getData();
            browse(path);
        });
    }

    @Override
    public void cleanRefresh() {
        if (Objects.nonNull(browsedPath)) {
            cleanPathMaps();
            refresh();
        }
    }

    @Override
    public void refresh() {
        if (Objects.nonNull(browsedPath)) {
            browse(browsedPath);
            treeView.requestFocus();
        }
    }

    @Override
    public void browse(final Path path) {

        if (!Objects.equals(path, browsedPath)) {
            cleanPathMaps();
        }

        this.browsedPath = path;

        threadService.runActionLater(() -> {

            current.currentEditor().updatePreviewUrl();

            this.treeView = controller.getFileSystemView();

            if (treeItemChangeListener == null) {
                treeItemChangeListener = (observable, oldValue, newValue) -> {
                    final MultipleSelectionModel<TreeItem<Item>> selectionModel = treeView.getSelectionModel();
                    if (!selectionModel.isEmpty()) {
                        saveTreeSelectionState(selectionModel);
                    }
                };
                treeView.getSelectionModel().selectedItemProperty().addListener(treeItemChangeListener);
            }

            rootItem = new PathItem<Item>(new Item(path, String.format("%s", Optional.of(path).map(Path::getFileName).orElse(path))), awesomeService.getIcon(path));
            rootItem.getChildren().add(new PathItem<Item>(new Item(null, "Loading..")));

            treeView.setRoot(rootItem);
            rootItem.setExpanded(true);

            this.addPathToTree(path, rootItem, null);

            logger.info("File browser relisted for {}", path);

        }, true);
    }

    private void cleanPathMaps() {
        expandedPaths.clear();
        pathItemMap.clear();
        directoryItemMap.clear();
    }

    @Override
    public void addPathToTree(Path path, final TreeItem<Item> treeItem, Path changedPath) {

        threadService.runTaskLater(() -> {

            if (Objects.isNull(path) || Objects.isNull(treeItem)) {
                return;
            }

            if (!Files.isDirectory(path)) {
                return;
            }

            if (!Files.exists(path)) {
                return;
            }

            if (treeItem == treeView.getRoot()) { // is root
                fileWatchService.reCreateWatchService();
            }

            directoryItemMap.put(path, treeItem);
            pathItemMap.put(path, treeItem);

            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path);) {

                List<TreeItem<Item>> subItemList = StreamSupport
                        .stream(directoryStream.spliterator(), false)
                        .filter(p -> controller.isShowHiddenFiles() || !isHidden(p))
                        .sorted(pathOrderService::comparePaths)
                        .map(p -> {
                            TreeItem<Item> childItem = new PathItem<Item>(new Item(p), awesomeService.getIcon(p));
                            if (Files.isDirectory(p)) {
                                if (!IOHelper.isEmptyDir(p)) {
                                    childItem.getChildren().add(new PathItem<Item>(new Item(null, "Loading..")));
                                }
                                childItem.setExpanded(expandedPaths.getOrDefault(p, false));
                                if (childItem.isExpanded()) {
                                    addPathToTree(p, childItem, null);
                                }
                                childItem.expandedProperty().addListener((observable, oldValue, newValue) -> {
                                    if (newValue) {
                                        addPathToTree(p, childItem, null);
                                    }
                                    expandedPaths.put(p, newValue);

                                    // fixes not expand issue
                                    treeView.refresh();
                                });
                            }
                            pathItemMap.put(p, childItem);
                            return childItem;
                        })
                        .collect(Collectors.toList());

                threadService.runActionLater(() -> {

                    treeItem.getChildren().clear();
                    treeView.getSelectionModel().clearSelection();
                    treeItem.getChildren().addAll(subItemList);

                    restoreTreeSelectionState();

                    focusPath(changedPath);

                    fileWatchService.registerPathWatcher(path);
                });

            } catch (Exception e) {
                logger.warn("Problem occured while updating file browser", e);
            }

        });

    }

    @Override
    public void focusPath(Path path) {
        if (Objects.nonNull(path)) {
            TreeItem<Item> item = pathItemMap.get(path);
            if (Objects.nonNull(item)) {
                TreeItem<Item> parent = item.getParent();
                while (Objects.nonNull(parent)) {
                    if (!parent.isExpanded()) {
                        parent.setExpanded(true);
                    }
                    parent = parent.getParent();
                }

                treeView.getSelectionModel().clearSelection();
                treeView.getSelectionModel().select(item);
                if (Objects.equals(item, treeView.getSelectionModel().getSelectedItem())) {
                    treeView.scrollTo(treeView.getSelectionModel().getSelectedIndex());
                }
            }
        }
    }

    private void restoreTreeSelectionState() {
        MultipleSelectionModel<TreeItem<Item>> selectionModel = treeView.getSelectionModel();
        selectionModel.setSelectionMode(SelectionMode.MULTIPLE);
        for (Path lastSelectedPath : lastSelectedItems) {
            TreeItem<Item> item = pathItemMap.get(lastSelectedPath);
            if (Objects.nonNull(item)) {
                selectionModel.select(item);
            }
        }
    }

    private void saveTreeSelectionState(MultipleSelectionModel<TreeItem<Item>> newValue) {
        try {
            lastSelectedItems = newValue
                    .getSelectedItems()
                    .stream()
                    .map(TreeItem::getValue)
                    .filter(Objects::nonNull)
                    .map(Item::getPath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

        } catch (Exception ex) {

        }
    }

    @Override
    public void refreshPathToTree(Path path, Path changedPath) {
        if (browsedPath == null) {
            return;
        }
        threadService.runActionLater(() -> {
            rootItem = new PathItem<Item>(new Item(browsedPath, String.format("%s", Optional.of(browsedPath).map(Path::getFileName).orElse(browsedPath))), awesomeService.getIcon(browsedPath));
            rootItem.getChildren().add(new PathItem<Item>(new Item(null, "Loading..")));

            treeView.setRoot(rootItem);
            rootItem.setExpanded(true);
            addPathToTree(browsedPath, rootItem, changedPath);
//            treeView.requestFocus();
        });
    }

    TreeItem<Item> searchFoundItem;

    @Override
    public void searchUpAndSelect(String text) {

        threadService.runTaskLater(() -> {
            List<TreeItem<Item>> foundItems = searchItems(text);

            if (foundItems.isEmpty()) {
                return;
            }

            ListIterator<TreeItem<Item>> listIterator = foundItems.listIterator();

            while (true) {

                if (Objects.isNull(searchFoundItem)) {
                    if (listIterator.hasNext()) {
                        searchFoundItem = listIterator.next();
                    }
                    break;
                }

                if (listIterator.hasNext()) {
                    TreeItem<Item> next = listIterator.next();
                    if (next.getValue().equals(searchFoundItem.getValue())) {
                        if (listIterator.hasNext()) {
                            TreeItem<Item> nexted = listIterator.next();

                            if (next == nexted) {
                                if (listIterator.hasNext()) {
                                    nexted = listIterator.next();
                                }
                            }

                            searchFoundItem = nexted;
                            break;
                        }
                    }
                } else {
                    break;
                }

            }

            focusFoundItem(searchFoundItem);
        });
    }

    @Override
    public void searchDownAndSelect(String text) {

        threadService.runTaskLater(() -> {
            List<TreeItem<Item>> foundItems = searchItems(text);

            if (foundItems.isEmpty()) {
                return;
            }

            ListIterator<TreeItem<Item>> listIterator = foundItems.listIterator();

            while (true) {

                if (Objects.isNull(searchFoundItem)) {
                    if (listIterator.hasPrevious()) {
                        searchFoundItem = listIterator.previous();
                    }

                    break;
                }

                if (listIterator.hasNext()) {
                    TreeItem<Item> next = listIterator.next();
                    if (next.getValue().equals(searchFoundItem.getValue())) {
                        if (listIterator.hasPrevious()) {
                            TreeItem<Item> previous = listIterator.previous();
                            if (next == previous) {
                                if (listIterator.hasPrevious()) {
                                    previous = listIterator.previous();
                                }
                            }
                            searchFoundItem = previous;
                            break;
                        }
                    }
                } else {
                    break;
                }

            }

            focusFoundItem(searchFoundItem);
        });
    }

    private void focusFoundItem(TreeItem<Item> searchFoundItem) {
        if (Objects.nonNull(searchFoundItem)) {

            TreeView<Item> fileSystemView = controller.getFileSystemView();
            threadService.runActionLater(() -> {

                MultipleSelectionModel<TreeItem<Item>> selectionModel = fileSystemView.getSelectionModel();
                selectionModel.clearSelection();
                selectionModel.select(searchFoundItem);
                fileSystemView.scrollTo(selectionModel.getSelectedIndex());

                TreeItem<Item> parent = searchFoundItem.getParent();
                if (Objects.nonNull(parent)) {
                    if (!parent.isExpanded()) {
                        parent.setExpanded(true);
                    }
                }
            }, true);
        }
    }

    private List<TreeItem<Item>> searchItems(String text) {

        PathMatcher pathMatcher = null;

        try {
            String syntaxAndPattern = String.format("glob:**%s**", text);
            pathMatcher = FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
        } catch (PatternSyntaxException psex) {
            return new ArrayList<>();
        }

        final PathMatcher finalPathMatcher = pathMatcher;

        Optional.ofNullable(searchFoundItem)
                .map(TreeItem::getValue)
                .map(Item::getPath)
                .filter(p -> !finalPathMatcher.matches(p))
                .ifPresent(p -> searchFoundItem = null);

        if (Objects.nonNull(searchFoundItem)) {
            if (!pathItemMap.containsValue(searchFoundItem)) {
                searchFoundItem = null;
            }
        }

        return pathItemMap.values()
                .stream()
                .map(e -> Optional.ofNullable(e))
                .filter(o -> o
                        .map(TreeItem::getValue)
                        .map(Item::getPath)
                        .filter(p -> !p.equals(p.getRoot()))
                        .filter(p -> finalPathMatcher.matches(p))
                        .isPresent())
                .map(e -> e.get())
                .sorted((p1, p2) -> pathOrderService.comparePaths(p1.getValue().getPath(), p2.getValue().getPath()))
                .collect(Collectors.toList());
    }

    @Override
    public void searchAndSelect(String text) {

        threadService.runTaskLater(() -> {

            List<TreeItem<Item>> foundItems = searchItems(text.trim());

            if (foundItems.isEmpty()) {
                return;
            }

            ListIterator<TreeItem<Item>> listIterator = foundItems.listIterator();


            if (Objects.isNull(searchFoundItem)) {
                if (listIterator.hasNext()) {
                    searchFoundItem = listIterator.next();
                }
            }

            focusFoundItem(searchFoundItem);
        });
    }
}
