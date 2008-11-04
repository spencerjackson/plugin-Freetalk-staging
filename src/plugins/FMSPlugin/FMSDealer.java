/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.FMSPlugin;

import freenet.support.Executor;
import freenet.support.Logger;

public class FMSDealer {
	
	private final Executor _executor;
	
	// services
	private FMSIdentityManager identManager;
	
	FMSDealer(Executor executor) {
		this._executor = executor;
		_executor.execute(new Runnable() {
			public void run() {
				startDealer();
			}}, "Dealer starter");
	}
	
	private void startDealer() {
		Logger.error(this, "Starting dealer", new Error("TODO"));
	}

	synchronized void killMe() {
		Logger.error(this, "Killing dealer", new Error("TODO"));
	}
}