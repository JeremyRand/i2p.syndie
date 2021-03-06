package syndie.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.MessageBox;
import syndie.data.MessageInfo;
import syndie.data.SyndieURI;

/**
 *  Contains a MessageView
 */
class MessageViewTab extends BrowserTab implements Translatable, Themeable {
    private MessageView _view;
    private String _name;
    private String _desc;
    
    public MessageViewTab(BrowserControl browser, SyndieURI uri, String suggestedName, String suggestedDesc) {
        super(browser, uri);
        _name = suggestedName;
        _desc = suggestedDesc;
        reconfigItem();
    }
    
    protected void initComponents() {
        getRoot().setLayout(new FillLayout());
        _view = new MessageView(getBrowser().getClient(), getBrowser().getUI(), getBrowser().getThemeRegistry(), getBrowser().getTranslationRegistry(), getBrowser().getNavControl(), URIHelper.instance(), getBrowser(), getBrowser(), getRoot(), getURI(), getBrowser());
        
        getBrowser().getThemeRegistry().register(this);
        getBrowser().getTranslationRegistry().register(this);
    }
    
    @Override
    public void tabShown() {
        String text = null;
        String msg = null;
        if (!_view.isKnownLocally()) {
            text = getText("Message unknown");
            msg = getText("The selected message is not known locally");
        } else if (_view.isReadKeyUnknown()) {
            text = getText("Read key unknown");
            msg = getText("You do not have the keys to decrypt this message");
        } else if (_view.isReplyKeyUnknown()) {
            text = getText("Reply key unknown");
            msg = getText("You do not have the keys to decrypt this message");
        }
        if (text != null) {
            MessageBox box = new MessageBox(getRoot().getShell(), SWT.ICON_INFORMATION | SWT.OK);
            box.setText(text);
            box.setMessage(msg);
            getBrowser().getNavControl().unview(getURI());
            box.open();
            return;
        }    
        _view.enable();
        _view.setKeyListener(true);
        super.tabShown();
    }
    
    @Override
    public void tabHidden() {
        _view.setKeyListener(false);
    }    

    protected void disposeDetails() { 
        _view.setKeyListener(false);
        _view.dispose();
        getBrowser().getTranslationRegistry().unregister(this);
        getBrowser().getThemeRegistry().unregister(this);
    }

    
    @Override
    public boolean canShow(SyndieURI uri) { 
        if (super.canShow(uri)) return true;
        if (uri == null) return false;
        if (uri.isChannel() && (uri.getScope() != null) && (uri.getMessageId() != null))
            return getURI().getScope().equals(uri.getScope()) && (getURI().getMessageId().equals(uri.getMessageId()));
        return false;
    }
    
    @Override
    public void show(SyndieURI uri) {
        if (uri.getPage() != null)
            _view.viewPage(uri.getPage().intValue());
        else if (uri.getAttachment() != null)
            _view.viewAttachment(uri.getAttachment().intValue());
    }
    
    @Override
    public void toggleMaxView() { _view.toggleMaxView(); }

    @Override
    public void toggleMaxEditor() { _view.toggleMaxEditor(); }
    
    @Override
    public Image getIcon() { return ImageUtil.ICON_TAB_MSG; }

    @Override
    public String getName() { return _name != null ? _name : _view.getTitle(); }

    @Override
    public String getDescription() { return _desc != null ? _desc : getURI().toString(); }

    public void translate(TranslationRegistry registry) {
        // nothing translatable
    }
    public void applyTheme(Theme theme) {
        // nothing themeable
    }
}
