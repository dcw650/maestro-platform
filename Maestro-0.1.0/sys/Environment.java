package sys;

import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;

import views.View;

/**
 * @author Zheng
 */
public class Environment implements Cloneable, Serializable {
    /**
	 * 
	 */
	private static final long serialVersionUID = 2857666625803572829L;
    
    public boolean violation;	//default is false: not a violation
    private Semaphore sem;
    private HashMap<String, View> views;
    
	/** Creates a new instance of VirtualView */
    public Environment() {
        views = new HashMap<String, View>();
        sem = new Semaphore(1);
        violation = false;
    }
    
    /** 
     * Creates a new instance of VirtualView from an existing views hashmap
     * @param vs the existing hashmap
     */
    public Environment(HashMap<String, View> vs) {
        views = vs;
        sem = new Semaphore(1);
        violation = false;
    }
    
    public void clearViews() {
        views.clear();
    }
    
    /**
	 * Add a new view instance and bind it to its name in the view reservoir
	 * This can also be used as update of one view instance
	 * @param name the name of the view instance
	 * @param view the view instance
	 * @return the previous view instance bound to this name, null if not existed
	 */
	public View addView(String name, View view) {
		//view.name = name;
		try {
			sem.acquire();
		} catch (InterruptedException e) {
			System.err.println("Resevorir_addViews: Inturrepted sem.acquire()");
			e.printStackTrace();
		}
		View ret = views.put(name, view);
		sem.release();
		return ret;
	}
	
	/**
	 * Remove the view instance bound to the name in the view reservoir
	 * @param name the name of the view instance to remove
	 * @return the removed view instance, null if not existed
	 */
	public View removeView(String name) {
		try {
			sem.acquire();
		} catch (InterruptedException e) {
			System.err.println("Resevorir_addViews: Inturrepted sem.acquire()");
			e.printStackTrace();
		}
		View ret = views.remove(name);
		sem.release();
		return ret;
	}
	
	/** 
	 * Get the specified view instance by the name from this view bucket
	 * @param name the name of the view instance
	 * @return the view instance, null if not existed
	 */
	public View getView(String name) {
		return views.get(name);
	}
    
    /** Get the view names from all those views this virtual view contains*/
    public Set<String> getViewNames() {
    	return views.keySet();
    }
    
    /**
     * Get all the view instances in this view bucket
     * @return the collection of view instances
     */
    public Collection<View> getAllViews() {
    	return views.values();
    }
    
    @SuppressWarnings("unchecked")
	public Environment clone() {
        Environment ret = new Environment((HashMap<String, View>)views.clone());
        return ret;
    }
    
    /* Do we really need multi-operations?
    public LinkedList<View> getViewsByNames(Set<String> viewNames) {
    	LinkedList<View> result = new LinkedList<View>();
    	Iterator<View> it = views.iterator();
    	while (it.hasNext()) {
    		View v = it.next();
    		if (viewNames.contains(v.name)) {
    			result.add(v);
    			// This seems like it should be a concurrent modification exception, and
    			// seems like it should be unnecessary.
    			// viewIDs.remove(v.viewID);
    		}
    	}
    	if(viewNames.size() != result.size()) {
    		//Debug info for bad view acquisition
    		System.out.println("BAD getViewsByIDS!! -- View IDs:   Result IDs:");
    		String[] vidArray = (String[]) viewNames.toArray();
    		View[] viewArray = (View[]) result.toArray();
    		for(int i=0;i<Math.max(vidArray.length,viewArray.length);i++) {
    			
    			if(i<vidArray.length) System.out.print(vidArray[i]+" ");
    			if(i<viewArray.length) System.out.print(viewArray[i].name+" ");
    			else System.out.println();
    		}
    		
    		Exception e = new Exception();
    		e.printStackTrace();
    	}
    	return result;
    }
    
    public void addViews(ViewsBucket vb) {
    	for(View v : vb.views) {
    		if (!vb.views.contains(v))
    			addView(v);
    	}
    }
    */
}