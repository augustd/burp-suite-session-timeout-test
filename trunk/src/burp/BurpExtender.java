package burp;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;

/**
 * Burp Extender to test for session timeout  
 * 
 * This extension attempts to determine how long it takes for a session to timeout at the server.
 * It issues the same request multiple times at increasing period until a condition (simple string) is matched.  
 * 
 * @author August Detlefsen <augustd at codemagi dot com>
 */
public class BurpExtender implements IBurpExtender, IContextMenuFactory {

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    protected IHttpRequestResponse[] requestResponse;
    private OutputStream output;

    //test parameters 
    private String timeoutMatchString;
    private int minSessionLength;
    private int maxSessionLength;
    private int interval; 
    
    //is the test running?
    private boolean isRunning = false;

    //text fields for the GUI
    private JTextField matchTextField = new JTextField("", 25); // With size and default text
    private JTextField minTextField = new JTextField("15", 3); // With size and default text
    private JTextField maxTextField = new JTextField("120", 3); // With size and default text
    private JTextField intervalTextField = new JTextField("1", 3); // With size and default text
    private JToggleButton toggleOnOff = new JToggleButton(isRunning ? "STOP TEST" : "START TEST");
    private JLabel statusLabel = new JLabel();
    private JLabel nextLabel = new JLabel();
    private JLabel nextIntervalLabel = new JLabel();
    private JLabel elapsedLabel = new JLabel();
    private JLabel remainingLabel = new JLabel();
    private JTabbedPane tabbedPane = new JTabbedPane();
	
    /**
     * implement IBurpExtender
     */
    @Override
    public void registerExtenderCallbacks(final IBurpExtenderCallbacks callbacks) {
	// keep a reference to our callbacks object
	this.callbacks = callbacks;

	// obtain an extension helpers object
	helpers = callbacks.getHelpers();

	// set our extension name
	callbacks.setExtensionName("Session Timeout Test");

	// register ourselves as a Context Menu Factory
	callbacks.registerContextMenuFactory(this);

	//get the menuItems stream for info messages
	output = callbacks.getStdout();
	
	println("Loaded Session Timeout Test");
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
	//get information from the invocation
	IHttpRequestResponse[] ihrrs = invocation.getSelectedMessages();
	
	List<JMenuItem> menuItems = new ArrayList<JMenuItem>();
	
	JMenuItem item = new JMenuItem("Test for Session Timout");
	item.addActionListener(new MenuItemListener(ihrrs));
	menuItems.add(item);
	
	return menuItems;
    }
    
    class MenuItemListener implements ActionListener {
	
	//private IHttpRequestResponse[] requestResponse;

	public MenuItemListener(IHttpRequestResponse[] ihrrs) {
	    requestResponse = ihrrs;
	}
	
	@Override
	public void actionPerformed(ActionEvent ae) {
	    //create a Java GUI to get the test parameters (match string, mix/max session length, increment)
	    initializeGUI();
	}
    }
    
    class StartScanListener implements ActionListener {

	public StartScanListener() {
	}

	@Override
	public void actionPerformed(ActionEvent e) {
	    //toggle status
	    isRunning = !isRunning;

	    JToggleButton button = (JToggleButton) e.getSource();

	    if (isRunning) {
		timeoutMatchString = matchTextField.getText();
		minSessionLength = Integer.parseInt(minTextField.getText());  //TODO: add better error handling
		maxSessionLength = Integer.parseInt(maxTextField.getText());  //TODO: add better error handling;
		interval = Integer.parseInt(intervalTextField.getText());  //TODO: add better error handling;

		launchScan(requestResponse);

		button.setText("STOP TEST");
		
		tabbedPane.setSelectedIndex(1);  //switch to the test status pane

	    } else {
		button.setText("START TEST");
	    }
	}
    }
    
    public void launchScan(IHttpRequestResponse[] ihrrs) {
	
	//this is only intended to work with ONE request
	if (ihrrs.length > 1) {
	    callbacks.issueAlert("The Session Timeout Test Extension only works with one request at a time -EXITING");
	    return;
	}
	
	IHttpRequestResponse baseRequestResponse = ihrrs[0];
	
	//launch a scanner thread
	TestThread theScan = new TestThread(baseRequestResponse);
	theScan.start();
    }
    
    class TestThread extends Thread {

	IHttpRequestResponse baseRequestResponse;

	public TestThread(IHttpRequestResponse baseRequestResponse) {
	    this.baseRequestResponse = baseRequestResponse;
	}

	@Override
	public void run() {
	    IHttpService service = baseRequestResponse.getHttpService();

	    //get the URL of the requst
	    URL url = helpers.analyzeRequest(baseRequestResponse).getUrl();
	    System.out.println("Testing for session timeout: " + url.toString());

	    byte[] request = baseRequestResponse.getRequest();
	    String requestAsString = new String(request);
	    System.out.println(requestAsString);
	    
	    int nextInterval = minSessionLength;
	    int timeUntilNextTest = minSessionLength * 60; //in seconds
	    int totalDuration = minutesRemaining(minSessionLength, maxSessionLength, interval) * 60;  //in seconds
	    int elapsedTime = 0;
	    boolean timeoutDetected = false;

	    while (isRunning && nextInterval <= maxSessionLength) {
		try {
		    sleep(1000); //sleep for one second
		} catch (InterruptedException ex) {
		    ex.printStackTrace();
		}

		if (timeUntilNextTest == 0) {
		    statusLabel.setText("Testing...");
		    System.out.println("TESTING: " + nextInterval + " minutes");
		    IHttpRequestResponse testRequestResponse = callbacks.makeHttpRequest(service, baseRequestResponse.getRequest());

		    byte[] response = testRequestResponse.getResponse();
		    String responseAsString = new String(response);
		    System.out.println("RESPONSE:");
		    System.out.println(responseAsString);

		    if (responseAsString.indexOf(timeoutMatchString) > 0) {
			//we have found the timeout! 
			System.out.println("SESSION TIMEOUT DETECTED! " + nextInterval + "minutes");
			timeoutDetected = true;
			break;

			//TODO: Add an informational entry to the scanner issues?
		    }
		    
		    nextInterval += interval;
		    timeUntilNextTest = nextInterval *60;
		}
		
		timeUntilNextTest--;
		totalDuration--;
		elapsedTime++;
		statusLabel.setText("Testing... ");
		nextIntervalLabel.setText(nextInterval + " minutes");
		nextLabel.setText(parseTime(timeUntilNextTest));
		elapsedLabel.setText(parseTime(elapsedTime));
		remainingLabel.setText(parseTime(totalDuration));
	    }
	    
	    if (timeoutDetected) {
		statusLabel.setText("Session timeout detected: " + nextInterval + " minutes");
	    } else {
		System.out.println("TEST COMPLETE. No session timeout detected.");
		statusLabel.setText("Test complete. No session timeout detected.");
	    }
	    
	    isRunning = false;
	    toggleOnOff.setSelected(isRunning);
	    toggleOnOff.setText("START TEST");
	    
	}
    }
    
    private static final String TIME_FORMAT = "%01d:%02d:%02d";
    
    
    public static String parseTime(int seconds) {
	return String.format(TIME_FORMAT,
			     TimeUnit.SECONDS.toHours(seconds),
			     TimeUnit.SECONDS.toMinutes(seconds) - TimeUnit.HOURS.toMinutes(TimeUnit.SECONDS.toHours(seconds)),
			     TimeUnit.SECONDS.toSeconds(seconds) - TimeUnit.MINUTES.toSeconds(TimeUnit.SECONDS.toMinutes(seconds)));
    }
    
    protected String formatTime(int numSeconds) {
	int m = numSeconds / 60;
	int s = numSeconds % 60;
	String output = m + ":";
	if (s < 10) output = output + "0";
	output = output + s;
	return output;
    }
    
    protected static int minutesRemaining(int min, int max, int interval) {
	int output = 0;
	for (int i = min; i <= max; i += interval) {
	    output += i; 
	}
	return output;
    }
    
    public static void main(String[] args) {
	System.out.println("timeRemaining(1,2,1): " + minutesRemaining(1,2,1) + " - " + parseTime(minutesRemaining(1,2,1) * 60));
	System.out.println("timeRemaining(1,4,1): " + minutesRemaining(1,4,1) + " - " + parseTime(minutesRemaining(1,4,1) * 60));
	System.out.println("timeRemaining(5,60,5): " + minutesRemaining(5,60,5) + " - " + parseTime(minutesRemaining(5,60,5) * 60));
	System.out.println("timeRemaining(1,120,1): " + minutesRemaining(1,120,1) + " - " + parseTime(minutesRemaining(1,120,1) * 60));
	
	BurpExtender be = new BurpExtender();
	be.initializeGUI();
    }
    
    private void initializeGUI() throws HeadlessException {
	JPanel outputPanel = new JPanel();
	outputPanel.setLayout(new BorderLayout());

	//setup the on/off button
	toggleOnOff.setSelected(isRunning);
	toggleOnOff.addActionListener(new StartScanListener());
	
	//the main control panel
	//JPanel controlPanel = new JPanel();

	//sub panel containing auto-sign controls
	GridLayout grid = new GridLayout(5, 2);
	grid.setHgap(4);
	
	//border for the status label 
	Border statusBorder = BorderFactory.createEmptyBorder(0, 5, 0, 0);
	statusLabel.setBorder(statusBorder);
	
	JPanel parameterPanel = new JPanel(grid);
	parameterPanel.setName("Session Timeout Test");
	parameterPanel.setBorder(new TitledBorder("Test Parameters"));

	parameterPanel.add(new JLabel("String to match:"), 0);
	parameterPanel.add(matchTextField, 1);

	parameterPanel.add(new JLabel("Minimum Session Duration:"), 2);
	parameterPanel.add(minTextField, 3);

	parameterPanel.add(new JLabel("Maximum Session Duration:"), 4);
	parameterPanel.add(maxTextField, 5);

	parameterPanel.add(new JLabel("Interval:"), 6);
	parameterPanel.add(intervalTextField, 7);
	
	//parameterPanel.add(toggleOnOff, 8);
	//parameterPanel.add(statusLabel, 9);

	//controlPanel.add(parameterPanel);

	//sub panel containing test information controls
	GridLayout infoGrid = new GridLayout(5, 2);
	infoGrid.setHgap(4);
	
	JPanel infoPanel = new JPanel(infoGrid);
	infoPanel.setName("Session Timeout Test");
	infoPanel.setBorder(new TitledBorder("Test Status"));

	infoPanel.add(new JLabel("Testing Interval:"), 0);
	infoPanel.add(nextIntervalLabel, 1);

	infoPanel.add(new JLabel("Next Test:"), 2);
	infoPanel.add(nextLabel, 3);

	infoPanel.add(new JLabel("Total Time Elapsed:"), 4);
	infoPanel.add(elapsedLabel, 5);

	infoPanel.add(new JLabel("Time Remaining:"), 6);
	infoPanel.add(remainingLabel, 7);
	
	//infoPanel.add(toggleOnOff, 8);

	//tabs within control panel window
	tabbedPane.addTab("Controls", parameterPanel);
	tabbedPane.addTab("Status", infoPanel);
	
	//start button and overall status
	JPanel bottomPanel = new JPanel(new GridLayout(1,2));
	bottomPanel.add(statusLabel, 0);
	bottomPanel.add(toggleOnOff, 1);
	
	//the whole GUI window
	JFrame gui = new JFrame("Session Timeout Test");
	gui.setLayout(new BorderLayout());
	gui.add(tabbedPane, BorderLayout.CENTER);
	gui.add(bottomPanel, BorderLayout.SOUTH);
	//gui.add(toggleOnOff, BorderLayout.SOUTH);
	//gui.add(statusLabel, BorderLayout.SOUTH);
	if (callbacks != null) callbacks.customizeUiComponent(gui);  //apply Burp's styles
	gui.pack();
	gui.setVisible(true);
    }

    private void println(String toPrint) {
	try {
	    output.write(toPrint.getBytes());
	    output.write("\n".getBytes());
	    output.flush();
	} catch (IOException ioe) {
	    ioe.printStackTrace();
	} 
    }
}
