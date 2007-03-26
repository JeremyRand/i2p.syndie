package syndie.gui;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.events.ShellListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import syndie.Constants;
import syndie.data.SyndieURI;
import syndie.data.Timer;
import syndie.db.DBClient;
import syndie.db.UI;

/**
 *
 */
class AttachmentPreview extends BaseComponent implements Translatable, Themeable {
    private Composite _parent;
    private Composite _root;
    private Label _nameLabel;
    private Text _name;
    private Label _descLabel;
    private Text _desc;
    private Label _sizeLabel;
    private Text _size;
    private Label _typeLabel;
    private Text _type;
    private ImageCanvas _preview;
    private Label _saveAsLabel;
    private Text _saveAs;
    private Button _saveAsBrowse;
    private Button _saveAsOk;
    
    private FileDialog _dialog;

    private byte _data[];
    private SyndieURI _uri;

    public AttachmentPreview(DBClient client, UI ui, ThemeRegistry themes, TranslationRegistry trans, Composite parent) {
        super(client, ui, themes, trans);
        _parent = parent;
        initComponents();
    }
    
    public interface AttachmentSource {
        public Properties getAttachmentConfig(int attachmentNum);
        public byte[] getAttachmentData(int attachmentNum);
    }
    
    private void initComponents() {
        _root = new Composite(_parent, SWT.NONE);
        _root.setLayout(new GridLayout(4, false));
        
        _nameLabel = new Label(_root, SWT.NONE);
        _nameLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        _name = new Text(_root, SWT.SINGLE | SWT.READ_ONLY | SWT.BORDER);
        _name.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        _preview = new ImageCanvas(_root, true);
        _preview.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true, 2, 4));
        //_preview.forceSize(64, 64);
        
        _descLabel = new Label(_root, SWT.NONE);
        _descLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        _desc = new Text(_root, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.BORDER);
        _desc.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        _sizeLabel = new Label(_root, SWT.NONE);
        _sizeLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        _size = new Text(_root, SWT.SINGLE | SWT.READ_ONLY | SWT.BORDER);
        _size.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        _typeLabel = new Label(_root, SWT.NONE);
        _typeLabel.setLayoutData(new GridData(GridData.END, GridData.BEGINNING, false, false));
        _type = new Text(_root, SWT.SINGLE | SWT.READ_ONLY | SWT.BORDER);
        _type.setLayoutData(new GridData(GridData.FILL, GridData.BEGINNING, false, false));
        
        _saveAsLabel = new Label(_root, SWT.NONE);
        _saveAsLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        _saveAs = new Text(_root, SWT.SINGLE | SWT.BORDER);
        _saveAs.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        _saveAsBrowse = new Button(_root, SWT.PUSH);
        _saveAsBrowse.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        _saveAsBrowse.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { browse(); }
            public void widgetSelected(SelectionEvent selectionEvent) { browse(); }
        });
        _saveAsOk = new Button(_root, SWT.PUSH);
        _saveAsOk.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        _saveAsOk.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { save(); }
            public void widgetSelected(SelectionEvent selectionEvent) { save(); }
        });
        
        _translationRegistry.register(this);
        _themeRegistry.register(this);
    }
    
    private static final String T_MAXVIEW_UNMAX = "syndie.gui.attachmentpreview.unmax";
    private Shell _maxShell;
    private ImageCanvas _maxImage;
    
    public void maximize() {
        if ( (_preview == null) || (_preview.getImage() == null) ) return;
        if (_maxShell != null) {
            unmax();
            return;
        }
        
        _maxShell = new Shell(_root.getShell(), SWT.NO_TRIM | SWT.PRIMARY_MODAL);
        _maxShell.setLayout(new GridLayout(1, true));
        Button unmax = new Button(_maxShell, SWT.PUSH);
        unmax.setText(_translationRegistry.getText(T_MAXVIEW_UNMAX, "Restore normal size"));
        unmax.setFont(_themeRegistry.getTheme().BUTTON_FONT);
        unmax.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));

        _maxImage = new ImageCanvas(_maxShell, true);
        _maxImage.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true, 1, 1));
        _maxImage.setImage(_preview.getImage());

        Monitor mon[] = _root.getDisplay().getMonitors();
        Rectangle rect = null;
        if ( (mon != null) && (mon.length > 1) )
            rect = mon[0].getClientArea();
        else
            rect = _parent.getDisplay().getClientArea();
        _maxShell.setSize(rect.width, rect.height);
        _maxShell.setMaximized(true);

        _maxShell.addShellListener(new ShellListener() {
            public void shellActivated(ShellEvent shellEvent) {}
            public void shellClosed(ShellEvent evt) {
                evt.doit = false;
                unmax();
            }
            public void shellDeactivated(ShellEvent shellEvent) {}
            public void shellDeiconified(ShellEvent shellEvent) {}
            public void shellIconified(ShellEvent shellEvent) {}
        });

        unmax.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { unmax(); }
            public void widgetSelected(SelectionEvent selectionEvent) { unmax(); }
            private void fire() { unmax(); }
        });

        _maxShell.open();
    }
    private void unmax() {
        if ( (_maxShell != null) && (!_maxShell.isDisposed()) )
            _maxShell.dispose();
        _maxShell = null;
        _maxImage = null; // leave the image disposal to the _preview
    }

    public SyndieURI getURI() { return _uri; }
    
    //public void showURI(SyndieURI uri) {
    
    public void showURI(AttachmentSource source, SyndieURI uri) {
        if (_data != null) return;
        _uri = uri;
        _ui.debugMessage("show URI: " + uri + " source=" + source);
        Timer timer = new Timer("show attachment", _ui);
        Long id = uri.getAttachment();
        if (id == null) return;
        int internalAttachmentNum = id.intValue()-1;
        Properties cfg = source.getAttachmentConfig(internalAttachmentNum);
        byte[] data = source.getAttachmentData(internalAttachmentNum);
        int bytes = (data != null ? data.length : 0);
        timer.addEvent("size fetched");
        
        if (cfg.containsKey(Constants.MSG_ATTACH_NAME))
            _name.setText(cfg.getProperty(Constants.MSG_ATTACH_NAME));
        else
            _name.setText("");
        if (cfg.containsKey(Constants.MSG_ATTACH_DESCRIPTION))
            _desc.setText(cfg.getProperty(Constants.MSG_ATTACH_DESCRIPTION));
        else
            _desc.setText("");
        
        String type = cfg.getProperty(Constants.MSG_ATTACH_CONTENT_TYPE);
        if (type == null)
            type = "application/octet-stream";
        _type.setText(type);
        
        _size.setText((bytes+1023)/1024 + " KB");
        
        timer.addEvent("options displayed");
        _data = data;
        timer.addEvent("data fetched");
        showPreviewIfPossible(type, _data, timer);
        timer.addEvent("data displayed");
        
        _saveAs.setText(_name.getText());
        timer.complete();
        
        //_shell.pack();
        //_shell.open();
    }
    
    private void showPreviewIfPossible(String contentType, byte data[], Timer timer) {
        Image old = _preview.getImage();
        ImageUtil.dispose(old);
        timer.addEvent("old disposed");
        boolean show = false;
        if (contentType.startsWith("image/") && (data != null)) {
            Image img = ImageUtil.createImage(data, _client.getTempDir());
            timer.addEvent("new image created");
            if (img != null) {
                _preview.setImage(img);
                timer.addEvent("new image set on preview");
                show = true;
            } else {
                show = false;
            }
        }
        if (show) {
            _preview.setVisible(true);
            timer.addEvent("new image preview shown");
            _ui.debugMessage("preview size: " + _preview.getSize() + " computed: " + _preview.computeSize(SWT.DEFAULT, SWT.DEFAULT));
            //gd.exclude = false;
        } else {
            _preview.setImage(null);
            _preview.setVisible(false);
            //gd.exclude = true;
        }
    }
    
    private void browse() {
        if (_dialog == null)
            _dialog = new FileDialog(_root.getShell(), SWT.SAVE);
        _dialog.setFileName(_saveAs.getText());
        String filename = _dialog.open();
        if (filename != null)
            _saveAs.setText(filename);
    }
    private void save() {
        FileOutputStream fos = null;
        String fname = _saveAs.getText().trim();
        File out = new File(fname);
        try {
            fos = new FileOutputStream(out);
            fos.write(_data);
            fos.close();
            fos = null;
            MessageBox box = new MessageBox(_root.getShell(), SWT.OK | SWT.ICON_INFORMATION);
            box.setText(_translationRegistry.getText(T_SAVE_OK_TITLE, "Attachment saved"));
            box.setMessage(_translationRegistry.getText(T_SAVE_OK_MSG, "Attachment saved to:") + out.getAbsolutePath());
            box.open();
        } catch (IOException ioe) {
            // hrm
            MessageBox box = new MessageBox(_root.getShell(), SWT.OK | SWT.ICON_ERROR);
            box.setText(_translationRegistry.getText(T_SAVE_ERROR_TITLE, "Error saving attachment"));
            box.setMessage(_translationRegistry.getText(T_SAVE_ERROR_MSG, "Attachment could not be saved: ") + ioe.getMessage());
            box.open();
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
    
    public void dispose() { 
        _translationRegistry.unregister(this); 
        _themeRegistry.unregister(this);
        unmax();
        _preview.disposeImage();
    }
    
    private static final String T_SAVE_OK_TITLE = "syndie.gui.attachmentpreviewpopup.save.ok.title";
    private static final String T_SAVE_OK_MSG = "syndie.gui.attachmentpreviewpopup.save.ok.msg";
    private static final String T_SAVE_ERROR_TITLE = "syndie.gui.attachmentpreviewpopup.save.error.title";
    private static final String T_SAVE_ERROR_MSG = "syndie.gui.attachmentpreviewpopup.save.error.msg";
    private static final String T_NAME = "syndie.gui.attachmentpreviewpopup.name";
    private static final String T_DESC = "syndie.gui.attachmentpreviewpopup.desc";
    private static final String T_SIZE = "syndie.gui.attachmentpreviewpopup.size";
    private static final String T_TYPE = "syndie.gui.attachmentpreviewpopup.type";
    private static final String T_SAVEAS = "syndie.gui.attachmentpreviewpopup.saveas";
    private static final String T_SAVEAS_BROWSE = "syndie.gui.attachmentpreviewpopup.saveas.browse";
    private static final String T_SAVE = "syndie.gui.attachmentpreviewpopup.save";
    
    public void translate(TranslationRegistry registry) {
        _nameLabel.setText(registry.getText(T_NAME, "Name:"));
        _descLabel.setText(registry.getText(T_DESC, "Description:"));
        _sizeLabel.setText(registry.getText(T_SIZE, "Size:"));
        _typeLabel.setText(registry.getText(T_TYPE, "Type:"));
        _saveAsLabel.setText(registry.getText(T_SAVEAS, "Save as:"));
        _saveAsBrowse.setText(registry.getText(T_SAVEAS_BROWSE, "Browse..."));
        _saveAsOk.setText(registry.getText(T_SAVE, "Save"));
    }
    
    public void applyTheme(Theme theme) {
        _desc.setFont(theme.CONTENT_FONT);
        _descLabel.setFont(theme.DEFAULT_FONT);
        _name.setFont(theme.CONTENT_FONT);
        _nameLabel.setFont(theme.DEFAULT_FONT);
        _saveAs.setFont(theme.CONTENT_FONT);
        _saveAsBrowse.setFont(theme.BUTTON_FONT);
        _saveAsLabel.setFont(theme.DEFAULT_FONT);
        _saveAsOk.setFont(theme.BUTTON_FONT);
        _size.setFont(theme.CONTENT_FONT);
        _sizeLabel.setFont(theme.DEFAULT_FONT);
        _type.setFont(theme.CONTENT_FONT);
        _typeLabel.setFont(theme.DEFAULT_FONT);
    }
}
