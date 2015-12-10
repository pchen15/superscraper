package scraper;
import java.util.ArrayList;
import java.util.Date;

public class SeminarEntry {
	Date date;
	ArrayList<String> speakers; //smart idea to make a speaker an object
	String location;
	String title;
	String abstrct;
	ArrayList<String> links; //any associated links
	
	public SeminarEntry(){
		date = null;
		speakers = new ArrayList<String>();
		links = new ArrayList<String>();
		location = null;
		title = null;
		abstrct = null;
	}
	
	public SeminarEntry(Date d, String s, String t){
		date = d;
		speakers = new ArrayList<String>();
		links = new ArrayList<String>();
		speakers.add(s);
		title = t;
		location = null;
		abstrct = null;
	}
	
	public void setdate(Date d){
		date = d;
	}
	
	public void settitle(String t){
		title = t;
	}
	
	public void addSpeaker(String s){
		speakers.add(s);
	}
	
	public void removeSpeaker(String s){
		speakers.remove(s);
	}
	
	public void clearSpeakers(){
		speakers = new ArrayList<String>();
	}
	
	public void addLink(String s){
		links.add(s);
	}
	
	public void removeLink(String s){
		links.remove(s);
	}
	
	public String toString(){
		return "(Date: " + date + ", Speakers: " + speakers + ", Title: " + title + ")";
	}
}
