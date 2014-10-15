/* 
 * This file is part of the PDF Split And Merge source code
 * Created on 16/mag/2014
 * Copyright 2013-2014 by Andrea Vacondio (andrea.vacondio@gmail.com).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as 
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.pdfsam.ui.selection.single;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.pdfsam.pdf.PdfDescriptorLoadingStatus.ENCRYPTED;
import static org.pdfsam.pdf.PdfDescriptorLoadingStatus.WITH_ERRORS;
import static org.pdfsam.ui.commons.SetDestinationRequest.requestDestination;
import static org.pdfsam.ui.commons.SetDestinationRequest.requestFallbackDestination;
import static org.sejda.eventstudio.StaticStudio.eventStudio;

import java.io.File;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.apache.commons.lang3.StringUtils;
import org.pdfsam.context.DefaultI18nContext;
import org.pdfsam.module.ModuleOwned;
import org.pdfsam.pdf.PdfDescriptorLoadingStatus;
import org.pdfsam.pdf.PdfDocumentDescriptor;
import org.pdfsam.pdf.PdfDocumentDescriptorProvider;
import org.pdfsam.pdf.PdfLoadRequestEvent;
import org.pdfsam.support.io.FileType;
import org.pdfsam.ui.commons.OpenFileRequest;
import org.pdfsam.ui.commons.ShowPdfDescriptorRequest;
import org.pdfsam.ui.commons.ShowStageRequest;
import org.pdfsam.ui.io.BrowsableFileField;
import org.pdfsam.ui.io.ChangedSelectedPdfVersionEvent;
import org.pdfsam.ui.selection.LoadingStatusIndicatorUpdater;
import org.pdfsam.ui.selection.PasswordFieldPopup;
import org.pdfsam.ui.support.FXValidationSupport.ValidationState;

import de.jensd.fx.fontawesome.AwesomeDude;
import de.jensd.fx.fontawesome.AwesomeIcon;

/**
 * Panel letting the user select a single input PDF document
 * 
 * @author Andrea Vacondio
 *
 */
public class SingleSelectionPane extends VBox implements ModuleOwned, PdfDocumentDescriptorProvider {

    private String ownerModule = StringUtils.EMPTY;
    private BrowsableFileField field = new BrowsableFileField(FileType.PDF);
    private Label details = new Label();
    private PdfDocumentDescriptor descriptor;
    private PasswordFieldPopup passwordPopup;
    private Label encryptionIndicator = new Label();

    private Consumer<PdfDocumentDescriptor> onLoaded = d -> {
        PdfDescriptorLoadingStatus status = d.loadingStatus().getValue();
        if (status == PdfDescriptorLoadingStatus.LOADED
                || status == PdfDescriptorLoadingStatus.LOADED_WITH_USER_PWD_DECRYPTION) {
            eventStudio().broadcast(requestFallbackDestination(d.getFile(), getOwnerModule()), getOwnerModule());
            eventStudio().broadcast(new ChangedSelectedPdfVersionEvent(d.getVersion()), getOwnerModule());
        }
    };

    private Consumer<PdfDocumentDescriptor> detailsUpdate = d -> {
        PdfDescriptorLoadingStatus status = d.loadingStatus().getValue();
        if (status == PdfDescriptorLoadingStatus.LOADED
                || status == PdfDescriptorLoadingStatus.LOADED_WITH_USER_PWD_DECRYPTION) {
            details.setText(DefaultI18nContext.getInstance().i18n("Pages: {0}, PDF Version: {1}",
                    Integer.toString(d.pages().getValue()), d.getVersionString()));
        } else if (status == PdfDescriptorLoadingStatus.LOADING) {
            details.setText(DefaultI18nContext.getInstance().i18n("Loading..."));
        } else {
            details.setText("");
        }
    };

    private Consumer<PdfDocumentDescriptor> encryptionIndicatorUpdate = new Consumer<PdfDocumentDescriptor>() {
        private LoadingStatusIndicatorUpdater updater = new LoadingStatusIndicatorUpdater(encryptionIndicator);

        public void accept(PdfDocumentDescriptor t) {
            updater.accept(t.loadingStatus().getValue());
        }
    };

    private ChangeListener<PdfDescriptorLoadingStatus> onLoadingStatusChange = (o, oldVal, newVal) -> {
        if (descriptor != null & !descriptor.isInvalid()) {
            encryptionIndicatorUpdate.andThen(detailsUpdate).andThen(onLoaded).accept(descriptor);
        }
    };

    public SingleSelectionPane(String ownerModule) {
        this.getStyleClass().add("single-selection-pane");
        this.ownerModule = defaultString(ownerModule);
        field.enforceValidation(true, false);
        passwordPopup = new PasswordFieldPopup(this.ownerModule);
        encryptionIndicator.getStyleClass().addAll("encryption-status");
        encryptionIndicator.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (descriptor.loadingStatus().getValue() == ENCRYPTED) {
                showPasswordFieldPopup();
            } else if (descriptor.loadingStatus().getValue() == WITH_ERRORS) {
                eventStudio().broadcast(new ShowStageRequest(), "LogStage");
            }
        });
        HBox.setMargin(encryptionIndicator, new Insets(0, 0, 0, 2));
        field.setGraphic(encryptionIndicator);
        field.getStyleClass().add("single-selection-top");
        HBox.setHgrow(field, Priority.ALWAYS);
        getChildren().addAll(field, details);
        field.getTextField().validProperty().addListener((o, oldVal, newVal) -> {
            if (newVal == ValidationState.VALID) {
                invalidateDescriptor();
                PdfLoadRequestEvent<PdfDocumentDescriptor> loadEvent = new PdfLoadRequestEvent<>(getOwnerModule());
                descriptor = PdfDocumentDescriptor.newDescriptorNoPassword(new File(field.getTextField().getText()));
                descriptor.loadingStatus().addListener(new WeakChangeListener<>(onLoadingStatusChange));
                field.getTextField().getContextMenu().getItems().forEach(i -> i.setDisable(false));
                loadEvent.add(descriptor);
                eventStudio().broadcast(loadEvent);
            } else {
                invalidateDescriptor();
                // TODO reset component status
            }
        });
        initContextMenu();
    }

    private void invalidateDescriptor() {
        if (descriptor != null) {
            descriptor.invalidate();
        }
    }

    private void showPasswordFieldPopup() {
        Scene scene = this.getScene();
        if (scene != null) {
            Window owner = scene.getWindow();
            if (owner != null && owner.isShowing()) {
                Point2D nodeCoord = encryptionIndicator.localToScene(encryptionIndicator.getWidth() / 2,
                        encryptionIndicator.getHeight() / 1.5);
                double anchorX = Math.round(owner.getX() + scene.getX() + nodeCoord.getX() + 2);
                double anchorY = Math.round(owner.getY() + scene.getY() + nodeCoord.getY() + 2);
                passwordPopup.showFor(this, descriptor, anchorX, anchorY);
            }
        }
    }

    public PdfDocumentDescriptor getPdfDocumentDescriptor() {
        return descriptor;
    }

    protected BrowsableFileField getField() {
        return field;
    }

    /**
     * to perform when the document is loaded
     * 
     * @param onDescriptorLoaded
     */
    public void addOnLoaded(Consumer<PdfDocumentDescriptor> onDescriptorLoaded) {
        this.onLoaded = onDescriptorLoaded.andThen(this.onLoaded);
    }

    private void initContextMenu() {
        MenuItem infoItem = createMenuItem(DefaultI18nContext.getInstance().i18n("Document properties"),
                AwesomeIcon.INFO);
        infoItem.setOnAction(e -> Platform.runLater(() -> eventStudio().broadcast(
                new ShowPdfDescriptorRequest(descriptor))));

        MenuItem setDestinationItem = createMenuItem(DefaultI18nContext.getInstance().i18n("Set destination"),
                AwesomeIcon.FILE_PDF_ALT);
        setDestinationItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.ALT_DOWN));
        setDestinationItem.setOnAction(e -> eventStudio().broadcast(
                requestDestination(descriptor.getFile(), getOwnerModule()), getOwnerModule()));

        MenuItem openFileItem = createMenuItem(DefaultI18nContext.getInstance().i18n("Open"), AwesomeIcon.FILE_ALT);
        openFileItem.setOnAction(e -> eventStudio().broadcast(new OpenFileRequest(descriptor.getFile())));

        MenuItem openFolderItem = createMenuItem(DefaultI18nContext.getInstance().i18n("Open Folder"),
                AwesomeIcon.FOLDER_ALTPEN);
        openFolderItem.setOnAction(e -> eventStudio().broadcast(
                new OpenFileRequest(descriptor.getFile().getParentFile())));

        field.getTextField().setContextMenu(
                new ContextMenu(setDestinationItem, new SeparatorMenuItem(), infoItem, openFileItem, openFolderItem));
    }

    private MenuItem createMenuItem(String text, AwesomeIcon icon) {
        MenuItem item = new MenuItem(text);
        AwesomeDude.setIcon(item, icon);
        item.setDisable(true);
        return item;
    }

    public String getOwnerModule() {
        return ownerModule;
    }

    public void setPromptText(String text) {
        field.getTextField().setPromptText(text);
    }
}
