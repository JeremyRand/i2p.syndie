package syndie.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import syndie.Constants;
import syndie.db.SharedArchiveEngine;
import syndie.db.SyncArchive;
import syndie.db.SyncManager;

/**
 *
 */
class SyndicatorDetailHTTPArchive implements Themeable, Translatable, Disposable, SyncArchive.SyncArchiveListener {
    private BrowserControl _browser;
    private Composite _parent;
    private SyncArchive _archive;
    
    private Composite _root;
    private Label _nameLabel;
    private Text _name;
    private Label _locationLabel;
    private Text _location;
    private Label _proxyHostLabel;
    private Text _proxyHost;
    private Label _proxyPortLabel;
    private Text _proxyPort;
    private Label _pushPolicyLabel;
    private Combo _pushPolicy;
    private Label _pushMaxSizeLabel;
    private Combo _pushMaxSize;
    private Label _pullPolicyLabel;
    private Combo _pullPolicy;
    private Label _pullMaxSizeLabel;
    private Combo _pullMaxSize;
    private Button _pullPrivate;
    private Button _pullPrivateLocalOnly;
    private Button _pullPBE;
    private Label _lastSyncLabel;
    private Label _lastSync;
    private Label _nextSyncLabel;
    private Label _nextSync;
    private Button _nextSyncNow;
    private Button _nextSyncNever;
    private Button _nextSyncOneOff;
    private Label _nextSyncDelayLabel;
    private Combo _nextSyncDelay;
    private Label _failuresLabel;
    private Label _failures;
    private Button _backOffOnFailures;
    private Button _save;
    private Button _cancel;
    
    public SyndicatorDetailHTTPArchive(BrowserControl browser, Composite parent, SyncArchive archive) {
        _browser = browser;
        _parent = parent;
        _archive = archive;
        initComponents();
        _archive.addListener(this);
    }
    
    public Control getControl() { return _root; }
    
    public void dispose() {
        if (!_root.isDisposed()) _root.dispose();
        _archive.removeListener(this);
        _browser.getTranslationRegistry().unregister(this);
        _browser.getThemeRegistry().unregister(this);
    }
    
    private void initComponents() {
        _root = new Composite(_parent, SWT.NONE);
        _root.setLayout(new GridLayout(2, false));
        
        // name row
        
        _nameLabel = new Label(_root, SWT.NONE);
        _nameLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        Composite row = new Composite(_root, SWT.NONE);
        row.setLayout(new GridLayout(3, false));
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _name = new Text(row, SWT.SINGLE | SWT.BORDER);
        _name.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _locationLabel = new Label(row, SWT.NONE);
        _locationLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        _location = new Text(row, SWT.SINGLE | SWT.BORDER);
        _location.setLayoutData(new GridData(200, SWT.DEFAULT));
        
        // last sync row
        
        _lastSyncLabel = new Label(_root, SWT.NONE);
        _lastSyncLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        row = new Composite(_root, SWT.NONE);
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        row.setLayout(new GridLayout(3, false));
        
        _lastSync = new Label(row, SWT.NONE);
        _lastSync.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
        
        _nextSyncLabel = new Label(row, SWT.NONE);
        _nextSyncLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        _nextSync = new Label(row, SWT.NONE);
        _nextSync.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, true, false));
        
        // next action row
        
        // stub for the first column
        new Composite(_root, SWT.NONE).setLayoutData(new GridData(1, 1));
        
        row = new Composite(_root, SWT.NONE);
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        row.setLayout(new GridLayout(5, false));
        
        _nextSyncNow = new Button(row, SWT.PUSH);
        _nextSyncNow.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _nextSyncNever = new Button(row, SWT.PUSH);
        _nextSyncNever.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _nextSyncOneOff = new Button(row, SWT.PUSH);
        _nextSyncOneOff.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _nextSyncDelayLabel = new Label(row, SWT.NONE);
        _nextSyncDelayLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        _nextSyncDelay = new Combo(row, SWT.DROP_DOWN | SWT.READ_ONLY | SWT.BORDER);
        _nextSyncDelay.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        // proxy row
        
        _proxyHostLabel = new Label(_root, SWT.NONE);
        _proxyHostLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        row = new Composite(_root, SWT.NONE);
        row.setLayout(new GridLayout(3, false));
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _proxyHost = new Text(row, SWT.SINGLE | SWT.BORDER);
        _proxyHost.setLayoutData(new GridData(100, SWT.DEFAULT));
        
        _proxyPortLabel = new Label(row, SWT.NONE);
        _proxyPortLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        _proxyPort = new Text(row, SWT.SINGLE | SWT.BORDER);
        _proxyPort.setLayoutData(new GridData(50, SWT.DEFAULT));
        
        // push policy row
        
        _pushPolicyLabel = new Label(_root, SWT.NONE);
        _pushPolicyLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        row = new Composite(_root, SWT.NONE);
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        row.setLayout(new GridLayout(3, false));
        
        _pushPolicy = new Combo(row, SWT.DROP_DOWN | SWT.BORDER | SWT.READ_ONLY);
        _pushPolicy.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _pushMaxSizeLabel = new Label(row, SWT.NONE);
        _pushMaxSizeLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        _pushMaxSize = new Combo(row, SWT.DROP_DOWN | SWT.BORDER | SWT.READ_ONLY);
        _pushMaxSize.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        // pull policy row
        
        _pullPolicyLabel = new Label(_root, SWT.NONE);
        _pullPolicyLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        row = new Composite(_root, SWT.NONE);
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        row.setLayout(new GridLayout(3, false));
        
        _pullPolicy = new Combo(row, SWT.DROP_DOWN | SWT.BORDER | SWT.READ_ONLY);
        _pullPolicy.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        _pullMaxSizeLabel = new Label(row, SWT.NONE);
        _pullMaxSizeLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        _pullMaxSize = new Combo(row, SWT.DROP_DOWN | SWT.BORDER | SWT.READ_ONLY);
        _pullMaxSize.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        // pull private row
        
        // stub for the first column
        new Composite(_root, SWT.NONE).setLayoutData(new GridData(1, 1));
        
        _pullPrivate = new Button(_root, SWT.CHECK);
        _pullPrivate.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        // pull private only row 
        
        // stub for the first column
        new Composite(_root, SWT.NONE).setLayoutData(new GridData(1, 1));
        
        _pullPrivateLocalOnly = new Button(_root, SWT.CHECK);
        _pullPrivateLocalOnly.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        // pull pbe row
        
        // stub for the first column
        new Composite(_root, SWT.NONE).setLayoutData(new GridData(1, 1));
        
        _pullPBE = new Button(_root, SWT.CHECK);
        _pullPBE.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        // failure row
        
        _failuresLabel = new Label(_root, SWT.NONE);
        _failuresLabel.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, false));
        
        row = new Composite(_root, SWT.NONE);
        row.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        row.setLayout(new GridLayout(2, false));
        
        _failures = new Label(row, SWT.NONE);
        _failures.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
        
        _backOffOnFailures = new Button(row, SWT.CHECK);
        _backOffOnFailures.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, false));
        
        // action row
        
        Composite actions = new Composite(_root, SWT.NONE);
        actions.setLayoutData(new GridData(GridData.FILL, GridData.BEGINNING, true, true, 2, 1));
        actions.setLayout(new FillLayout(SWT.HORIZONTAL));
        
        _save = new Button(actions, SWT.PUSH);
        _cancel = new Button(actions, SWT.PUSH);
        
        configActions();
        
        _browser.getTranslationRegistry().register(this);
        _browser.getThemeRegistry().register(this);
        
        loadData();
    }
    
    private void configActions() {
        _nextSyncNever.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { fire(); }
            public void widgetSelected(SelectionEvent selectionEvent) { fire(); }
            private void fire() {
                _archive.setNextPullTime(-1);
                _archive.setNextPushTime(-1);
                _archive.setNextPullOneOff(false);
                _archive.setNextPushOneOff(false);
                _archive.store(true);
            }
        });
        _nextSyncNow.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { fire(); }
            public void widgetSelected(SelectionEvent selectionEvent) { fire(); }
            private void fire() {
                _archive.setNextPullTime(System.currentTimeMillis());
                _archive.setNextPushTime(System.currentTimeMillis());
                _archive.setNextPullOneOff(false);
                _archive.setNextPushOneOff(false);
                _archive.store(true);
            }
        });
        _nextSyncOneOff.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { fire(); }
            public void widgetSelected(SelectionEvent selectionEvent) { fire(); }
            private void fire() {
                _archive.setNextPullTime(System.currentTimeMillis());
                _archive.setNextPushTime(System.currentTimeMillis());
                _archive.setNextPullOneOff(true);
                _archive.setNextPushOneOff(true);
                _archive.store(true);
            }
        });
        
        _cancel.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { loadData(); }
            public void widgetSelected(SelectionEvent selectionEvent) { loadData(); }
        });
        _save.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { save(); }
            public void widgetSelected(SelectionEvent selectionEvent) { save(); }
        });
    }
    
    private void save() {
        _archive.setName(_name.getText());
        String host = _proxyHost.getText().trim();
        int port = -1;
        if (host.length() > 0) {
            String str = _proxyPort.getText().trim();
            try {
                port = Integer.parseInt(str);
            } catch (NumberFormatException nfe) {
                host = null;
            }
        } else {
            host = null;
        }
        _archive.setHTTPProxyHost(host);
        _archive.setHTTPProxyPort(port);
        
        SharedArchiveEngine.PullStrategy pullStrategy = createPullStrategy();
        _archive.setPullStrategy(pullStrategy);
        
        SharedArchiveEngine.PushStrategy pushStrategy = createPushStrategy();
        _archive.setPushStrategy(pushStrategy);
        
        _archive.setURL(_location.getText().trim());
        
        _archive.store(true);
    }
    
    private SharedArchiveEngine.PullStrategy createPullStrategy() {
        SharedArchiveEngine.PullStrategy pullStrategy = new SharedArchiveEngine.PullStrategy();
        
        pullStrategy.discoverArchives = true;
        pullStrategy.includePBEMessages = _pullPBE.getSelection();
        pullStrategy.includePrivateMessages = _pullPrivate.getSelection();
        pullStrategy.maxKBPerMessage = SIZES[_pullMaxSize.getSelectionIndex()];
        
        switch (_pullPolicy.getSelectionIndex()) {
            case PULL_POLICY_ALL_DELTA: 
                pullStrategy.knownChannelsOnly = false;
                pullStrategy.includeRecentMessagesOnly = false;
                pullStrategy.pullNothing = false;
                pullStrategy.includeDupForPIR = false;
                break;
            case PULL_POLICY_ALL_KNOWN:
                pullStrategy.knownChannelsOnly = true;
                pullStrategy.includeRecentMessagesOnly = false;
                pullStrategy.pullNothing = false;
                pullStrategy.includeDupForPIR = false;
                break;
            case PULL_POLICY_NOTHING:
                pullStrategy.knownChannelsOnly = false;
                pullStrategy.includeRecentMessagesOnly = false;
                pullStrategy.pullNothing = true;
                pullStrategy.includeDupForPIR = false;
                break;
            case PULL_POLICY_PIR: 
                pullStrategy.knownChannelsOnly = false;
                pullStrategy.includeRecentMessagesOnly = false;
                pullStrategy.pullNothing = false;
                pullStrategy.includeDupForPIR = true;
                break;
            case PULL_POLICY_RECENT_DELTA:
                pullStrategy.knownChannelsOnly = false;
                pullStrategy.includeRecentMessagesOnly = true;
                pullStrategy.pullNothing = false;
                pullStrategy.includeDupForPIR = false;
                break;
            case PULL_POLICY_RECENT_KNOWN:
                pullStrategy.knownChannelsOnly = true;
                pullStrategy.includeRecentMessagesOnly = true;
                pullStrategy.pullNothing = false;
                pullStrategy.includeDupForPIR = false;
                break;
        }
        return pullStrategy;
    }
    
    private SharedArchiveEngine.PushStrategy createPushStrategy() {
        SharedArchiveEngine.PushStrategy pushStrategy = new SharedArchiveEngine.PushStrategy();
        
        pushStrategy.maxKBPerMessage = SIZES[_pushMaxSize.getSelectionIndex()];
        
        switch (_pushPolicy.getSelectionIndex()) {
            case PUSH_POLICY_ALL_DELTA: 
                pushStrategy.sendLocalNewOnly = false;
                pushStrategy.sendNothing = false;
                break;
            case PUSH_POLICY_LOCAL_DELTA:
                pushStrategy.sendLocalNewOnly = true;
                pushStrategy.sendNothing = false;
                break;
            case PUSH_POLICY_NOTHING:
                pushStrategy.sendLocalNewOnly = false;
                pushStrategy.sendNothing = true;
                break;
        }
        return pushStrategy;
    }
    
    private static final String str(String str) { return str != null ? str : ""; }
    
    private SharedArchiveEngine.PushStrategy getPushStrategy() {
        SharedArchiveEngine.PushStrategy rv = _archive.getPushStrategy();
        if (rv == null)
            rv = SyncManager.getInstance(_browser.getClient(), _browser.getUI()).getDefaultPushStrategy();
        return rv;
    }
    
    private SharedArchiveEngine.PullStrategy getPullStrategy() {
        SharedArchiveEngine.PullStrategy rv = _archive.getPullStrategy();
        if (rv == null)
            rv = SyncManager.getInstance(_browser.getClient(), _browser.getUI()).getDefaultPullStrategy();
        return rv;
    }
    
    private void loadData() {
        _name.setText(str(_archive.getName()));
        _location.setText(str(_archive.getURL()));
        _proxyHost.setText(str(_archive.getHTTPProxyHost()));
        _proxyPort.setText(_archive.getHTTPProxyPort() > 0 ? _archive.getHTTPProxyPort()+"" : "");
        
        SharedArchiveEngine.PushStrategy push = getPushStrategy();
        if (push.sendLocalNewOnly)
            _pushPolicy.select(PUSH_POLICY_LOCAL_DELTA);
        else if (push.sendNothing)
            _pushPolicy.select(PUSH_POLICY_NOTHING);
        else
            _pushPolicy.select(PUSH_POLICY_ALL_DELTA);
        
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i] >= push.maxKBPerMessage) {
                _pushMaxSize.select(i);
                break;
            }
        }
        
        SharedArchiveEngine.PullStrategy pull = getPullStrategy();
        if (pull.includeDupForPIR) {
            _pullPolicy.select(PULL_POLICY_PIR);
        } else if (pull.pullNothing) {
            _pullPolicy.select(PULL_POLICY_NOTHING);
        } else if (pull.includeRecentMessagesOnly) {
            if (pull.knownChannelsOnly)
                _pullPolicy.select(PULL_POLICY_RECENT_KNOWN);
            else
                _pullPolicy.select(PULL_POLICY_RECENT_DELTA);
        } else {
            if (pull.knownChannelsOnly)
                _pullPolicy.select(PULL_POLICY_ALL_KNOWN);
            else
                _pullPolicy.select(PULL_POLICY_ALL_DELTA);
        }
        
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i] >= pull.maxKBPerMessage) {
                _pullMaxSize.select(i);
                break;
            }
        }
        
        _pullPrivate.setSelection(pull.includePrivateMessages);
        _pullPrivateLocalOnly.setEnabled(false);
        _pullPBE.setSelection(pull.includePBEMessages);
        
        long last = Math.max(_archive.getLastPullTime(), _archive.getLastPushTime());
        if (last > 0)
            _lastSync.setText(Constants.getDateTime(last));
        else
            _lastSync.setText(_browser.getTranslationRegistry().getText(T_NEVER, "Never"));
        
        long nxt = Math.max(_archive.getNextPullTime(), _archive.getNextPushTime());
        if (nxt > 0) {
            if (nxt <= System.currentTimeMillis())
                _nextSync.setText(_browser.getTranslationRegistry().getText(T_ASAP, "ASAP"));
            else
                _nextSync.setText(Constants.getDateTime(nxt));
        } else {
            _nextSync.setText(_browser.getTranslationRegistry().getText(T_NEVER, "Never"));
        }
    
        _nextSyncDelay.setEnabled(false);
        _failures.setText(_archive.getConsecutiveFailures() + "");
        _backOffOnFailures.setEnabled(false);
    }
    
    private static final String T_NEVER = "syndie.gui.syndicatordetailhttparchive.never";
    private static final String T_ASAP = "syndie.gui.syndicatordetailhttparchive.asap";

    // callbacks from the archive engine, may occur on arbitrary threads
    public void incomingUpdated(SyncArchive.IncomingAction action) {}
    public void outgoingUpdated(SyncArchive.OutgoingAction action) {}
    public void archiveUpdated(SyncArchive archive) { 
        Display.getDefault().asyncExec(new Runnable() { public void run() { loadData(); _root.layout(true, true); } });
    }
    
    public void applyTheme(Theme theme) {    
        _nameLabel.setFont(theme.DEFAULT_FONT);
        _name.setFont(theme.DEFAULT_FONT);
        _locationLabel.setFont(theme.DEFAULT_FONT);
        _location.setFont(theme.DEFAULT_FONT);
        _proxyHostLabel.setFont(theme.DEFAULT_FONT);
        _proxyHost.setFont(theme.DEFAULT_FONT);
        _proxyPortLabel.setFont(theme.DEFAULT_FONT);
        _proxyPort.setFont(theme.DEFAULT_FONT);
        _pushPolicyLabel.setFont(theme.DEFAULT_FONT);
        _pushPolicy.setFont(theme.DEFAULT_FONT);
        _pushMaxSizeLabel.setFont(theme.DEFAULT_FONT);
        _pushMaxSize.setFont(theme.DEFAULT_FONT);
        _pullPolicyLabel.setFont(theme.DEFAULT_FONT);
        _pullPolicy.setFont(theme.DEFAULT_FONT);
        _pullMaxSizeLabel.setFont(theme.DEFAULT_FONT);
        _pullMaxSize.setFont(theme.DEFAULT_FONT);
        _lastSyncLabel.setFont(theme.DEFAULT_FONT);
        _lastSync.setFont(theme.DEFAULT_FONT);
        _nextSyncLabel.setFont(theme.DEFAULT_FONT);
        _nextSync.setFont(theme.DEFAULT_FONT);
        _pullPrivate.setFont(theme.DEFAULT_FONT);
        _pullPrivateLocalOnly.setFont(theme.DEFAULT_FONT);
        _pullPBE.setFont(theme.DEFAULT_FONT);
        _nextSyncDelayLabel.setFont(theme.DEFAULT_FONT);
        _nextSyncDelay.setFont(theme.DEFAULT_FONT);
        _failuresLabel.setFont(theme.DEFAULT_FONT);
        _failures.setFont(theme.DEFAULT_FONT);
        _backOffOnFailures.setFont(theme.DEFAULT_FONT);
    
        _nextSyncNow.setFont(theme.BUTTON_FONT);
        _nextSyncNever.setFont(theme.BUTTON_FONT);
        _nextSyncOneOff.setFont(theme.BUTTON_FONT);
        _save.setFont(theme.BUTTON_FONT);
        _cancel.setFont(theme.BUTTON_FONT);
    }
    
    private static final String T_NAME = "syndie.gui.syndicatordetailhttparchive.name";
    private static final String T_LOCATION = "syndie.gui.syndicatordetailhttparchive.location";
    private static final String T_PROXYHOST = "syndie.gui.syndicatordetailhttparchive.proxyhost";
    private static final String T_PROXYPORT = "syndie.gui.syndicatordetailhttparchive.proxyport";
    private static final String T_PUSHPOLICY = "syndie.gui.syndicatordetailhttparchive.pushpolicy";
    private static final String T_PUSHMAXSIZE = "syndie.gui.syndicatordetailhttparchive.pushmaxsize";
    private static final String T_PULLPOLICY = "syndie.gui.syndicatordetailhttparchive.pullpolicy";
    private static final String T_PULLMAXSIZE = "syndie.gui.syndicatordetailhttparchive.pullmaxsize";
    private static final String T_PULLPRIVATE = "syndie.gui.syndicatordetailhttparchive.pullprivate";
    private static final String T_PULLPRIVATELOCALONLY = "syndie.gui.syndicatordetailhttparchive.pullprivateonly";
    private static final String T_PBE = "syndie.gui.syndicatordetailhttparchive.pbe";
    private static final String T_LASTSYNC = "syndie.gui.syndicatordetailhttparchive.lastsync";
    private static final String T_NEXTSYNC = "syndie.gui.syndicatordetailhttparchive.nextsync";
    private static final String T_SYNCNOW = "syndie.gui.syndicatordetailhttparchive.syncnow";
    private static final String T_SYNCNEVER = "syndie.gui.syndicatordetailhttparchive.syncnever";
    private static final String T_SYNCONEOFF = "syndie.gui.syndicatordetailhttparchive.synconeoff";
    private static final String T_SYNCDELAY = "syndie.gui.syndicatordetailhttparchive.syncdelay";
    private static final String T_FAILURES = "syndie.gui.syndicatordetailhttparchive.failures";
    private static final String T_BACKOFF = "syndie.gui.syndicatordetailhttparchive.backoff";
    private static final String T_PUSHPOLICY_ALLDELTA = "syndie.gui.syndicatordetailhttparchive.pushpolicy.alldelta";
    private static final String T_PUSHPOLICY_LOCALDELTA = "syndie.gui.syndicatordetailhttparchive.pushpolicy.localdelta";
    private static final String T_PUSHPOLICY_NOTHING = "syndie.gui.syndicatordetailhttparchive.pushpolicy.nothing";
    private static final String T_SIZE_SUFFIX = "syndie.gui.syndicatordetailhttparchive.sizesuffix";
    private static final String T_PULLPOLICY_RECENTDELTA = "syndie.gui.syndicatordetailhttparchive.pullpolicy.recentdelta";
    private static final String T_PULLPOLICY_ALLDELTA = "syndie.gui.syndicatordetailhttparchive.pullpolicy.alldelta";
    private static final String T_PULLPOLICY_RECENTKNOWN = "syndie.gui.syndicatordetailhttparchive.pullpolicy.recentknown";
    private static final String T_PULLPOLICY_ALLKNOWN = "syndie.gui.syndicatordetailhttparchive.pullpolicy.allknown";
    private static final String T_PULLPOLICY_PIR = "syndie.gui.syndicatordetailhttparchive.pullpolicy.pir";
    private static final String T_PULLPOLICY_NOTHING = "syndie.gui.syndicatordetailhttparchive.pullpolicy.nothing";
    private static final String T_SYNC_DELAY_SUFFIX = "syndie.gui.syndicatordetailhttparchive.syncdelaysuffix";
    private static final String T_SAVE = "syndie.gui.syndicatordetailhttparchive.save";
    private static final String T_CANCEL = "syndie.gui.syndicatordetailhttparchive.cancel";
    
    public void translate(TranslationRegistry registry) {
        _nameLabel.setText(registry.getText(T_NAME, "Name:"));
        _locationLabel.setText(registry.getText(T_LOCATION, "Archive URL:"));
        _proxyHostLabel.setText(registry.getText(T_PROXYHOST, "Proxy host:"));
        _proxyPortLabel.setText(registry.getText(T_PROXYPORT, "Port:"));
        _pushPolicyLabel.setText(registry.getText(T_PUSHPOLICY, "Push policy:"));
        _pushMaxSizeLabel.setText(registry.getText(T_PUSHMAXSIZE, "Max message size:"));
        _pullPolicyLabel.setText(registry.getText(T_PULLPOLICY, "Pull policy:"));
        _pullMaxSizeLabel.setText(registry.getText(T_PULLMAXSIZE, "Max message size:"));
        _pullPrivate.setText(registry.getText(T_PULLPRIVATE, "Pull any private messages?"));
        _pullPrivateLocalOnly.setText(registry.getText(T_PULLPRIVATELOCALONLY, "Pull private messages for us only?"));
        _pullPBE.setText(registry.getText(T_PBE, "Pull passphrase protected messages?"));
        _lastSyncLabel.setText(registry.getText(T_LASTSYNC, "Last sync:"));
        _nextSyncLabel.setText(registry.getText(T_NEXTSYNC, "Next sync:"));
        _nextSyncNow.setText(registry.getText(T_SYNCNOW, "Sync ASAP"));
        _nextSyncNever.setText(registry.getText(T_SYNCNEVER, "Never sync"));
        _nextSyncOneOff.setText(registry.getText(T_SYNCONEOFF, "Sync only once"));
        _nextSyncDelayLabel.setText(registry.getText(T_SYNCDELAY, "Min sync delay:"));
        _failuresLabel.setText(registry.getText(T_FAILURES, "Sync failures:"));
        _backOffOnFailures.setText(registry.getText(T_BACKOFF, "Back off after failing?"));
        _save.setText(registry.getText(T_SAVE, "Save"));
        _cancel.setText(registry.getText(T_CANCEL, "Cancel"));

        translateCombos(registry);
    }
    
    private static final int PUSH_POLICY_ALL_DELTA = 0;
    private static final int PUSH_POLICY_LOCAL_DELTA = 1;
    private static final int PUSH_POLICY_NOTHING = 2;
    private static final int PUSH_POLICY_DEFAULT = PUSH_POLICY_ALL_DELTA;
    
    private static final int PULL_POLICY_RECENT_DELTA = 0;
    private static final int PULL_POLICY_ALL_DELTA = 1;
    private static final int PULL_POLICY_RECENT_KNOWN = 2;
    private static final int PULL_POLICY_ALL_KNOWN = 3;
    private static final int PULL_POLICY_PIR = 4;
    private static final int PULL_POLICY_NOTHING = 5;
    private static final int PULL_POLICY_DEFAULT = 0;
    
    private static final int[] SIZES = new int[] { 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096 };
    private static final int SIZE_DEFAULT_INDEX = 7; // 512KB
    
    private static final int[] SYNC_DELAY = new int[] { 1, 2, 4, 6, 12, 18, 24 };
    private static final int SYNC_DELAY_DEFAULT_INDEX = 0;
    
    private void translateCombos(TranslationRegistry registry) {
        int cnt = _pushPolicy.getItemCount();
        int sel = (cnt > 0 ? _pushPolicy.getSelectionIndex() : PUSH_POLICY_DEFAULT);
        _pushPolicy.removeAll();
        _pushPolicy.add(registry.getText(T_PUSHPOLICY_ALLDELTA, "Send all messages they don't have"));
        _pushPolicy.add(registry.getText(T_PUSHPOLICY_LOCALDELTA, "Send locally created messages they don't have"));
        _pushPolicy.add(registry.getText(T_PUSHPOLICY_NOTHING, "Send nothing"));
        _pushPolicy.select(sel);
        
        cnt = _pushMaxSize.getItemCount();
        sel = (cnt > 0 ? _pushMaxSize.getSelectionIndex() : SIZE_DEFAULT_INDEX);
        _pushMaxSize.removeAll();
        for (int i = 0; i < SIZES.length; i++)
            _pushMaxSize.add(SIZES[i] + registry.getText(T_SIZE_SUFFIX, " KBytes"));
        _pushMaxSize.select(sel);
        
        cnt = _pullPolicy.getItemCount();
        sel = (cnt > 0 ? _pullPolicy.getSelectionIndex() : PULL_POLICY_DEFAULT);
        _pullPolicy.removeAll();
        _pullPolicy.add(registry.getText(T_PULLPOLICY_RECENTDELTA, "Recent messages we don't have"));
        _pullPolicy.add(registry.getText(T_PULLPOLICY_ALLDELTA, "All messages we don't have"));
        _pullPolicy.add(registry.getText(T_PULLPOLICY_RECENTKNOWN, "Recent messages in forums we know"));
        _pullPolicy.add(registry.getText(T_PULLPOLICY_ALLKNOWN, "All messages in forums we know"));
        _pullPolicy.add(registry.getText(T_PULLPOLICY_PIR, "Everything the archive considers 'new' (PIR)"));
        _pullPolicy.add(registry.getText(T_PULLPOLICY_NOTHING, "Nothing"));
        _pullPolicy.select(sel);
        
        cnt = _pullMaxSize.getItemCount();
        sel = (cnt > 0 ? _pullMaxSize.getSelectionIndex() : SIZE_DEFAULT_INDEX);
        _pullMaxSize.removeAll();
        for (int i = 0; i < SIZES.length; i++)
            _pullMaxSize.add(SIZES[i] + registry.getText(T_SIZE_SUFFIX, "KBytes"));
        _pullMaxSize.select(sel);
        
        cnt = _nextSyncDelay.getItemCount();
        sel = (cnt > 0 ? _nextSyncDelay.getSelectionIndex() : SYNC_DELAY_DEFAULT_INDEX);
        _nextSyncDelay.removeAll();
        for (int i = 0; i < SYNC_DELAY.length; i++)
            _nextSyncDelay.add(SYNC_DELAY[i] + registry.getText(T_SYNC_DELAY_SUFFIX, " hour(s)"));
        _nextSyncDelay.select(sel);
    }
}
