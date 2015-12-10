package scraper;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import static scraper.Utilities.*;

import edu.emory.mathcs.backport.java.util.Collections;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.ling.CoreLabel;

public class ScraperMain {
	public static void main(String args[]) throws Exception{
		setBannedWords();
		
		//obtaining input
		Scanner input = new Scanner(System.in);
		System.out.println("How many urls?"); int n;
		try {
			n = input.nextInt();
		} catch(Exception e){
			System.out.println("Not a number. Terminating...");
			input.close();
			System.out.println("closed.");
			return;
		}
		String[] urls = new String[n];
		System.out.println("Input " + n + " urls:");
		for(int i=0; i<n; i++){
			urls[i] = input.next();
		}
		input.close();
		
		//connect to the urls provided, obtain a list of all entries, and fetch embedded hyperlinks as well
		ArrayList<String> originalcontents = createfilecontents(urls);
		ArrayList<LinkedHashMap<String, String>> all_links = new ArrayList<LinkedHashMap<String, String>>();
		System.out.println("Fetching hyperlinks...");
		all_links = getlinks(urls);
		System.out.println("Finished fetching links");
		
		//initialize variables
		ArrayList<String> superlst = new ArrayList<String>();
		ArrayList<Date> dates = new ArrayList<Date>();
		ArrayList<LinkedHashMap<Date, ArrayList<String>>> date2contents = new ArrayList<LinkedHashMap<Date, ArrayList<String>>>();
		ArrayList<String> entrycontents = new ArrayList<String>();
		String preventry = "";
		Date tmpdate = null;
		boolean firstdate = false;
		
		//set up date2contents so that it is an array of maps
		//each map corresponds to a date and an arraylist of entries associated with that date/talk
		System.out.println("Processing...");
		for(String htmlcontent : originalcontents){
			superlst = splitHTML(htmlcontent);
			LinkedHashMap<Date, ArrayList<String>> doccontents = new LinkedHashMap<Date, ArrayList<String>>();
			for(String entry : superlst){
				dates = parseDate(entry, true);
				try{
					if(dates.size() == 0) dates = parseDate(preventry + " " + entry, true); //basically a hack to catch more dates
				} catch(Exception e){}
				
				if(dates.size() > 0) {
					if(firstdate && tmpdate != null){
						doccontents.put(tmpdate, entrycontents);
					}
					entrycontents = new ArrayList<String>();
					firstdate = true;
					tmpdate = dates.get(0);
				}
			
				if(firstdate){
					if(entry.trim().length() > 0) entrycontents.add(entry);
				}
				preventry = entry;
			}
			doccontents.put(tmpdate, entrycontents);
			date2contents.add(doccontents);
			entrycontents = new ArrayList<String>();
			tmpdate = null;
			//System.out.println(htmlcontent);
		}

		//set up classifiers and more variables for title frequencies
		String serializedClassifier = "classifiers/english.all.3class.distsim.crf.ser.gz";
		AbstractSequenceClassifier<CoreLabel> classifier = CRFClassifier
                .getClassifierNoExceptions(serializedClassifier);
		
		ArrayList<ArrayList<SeminarEntry>> all_seminars = new ArrayList<ArrayList<SeminarEntry>>();
		ArrayList<String> date_contents = new ArrayList<String>();
		Set<String> tags = new HashSet<String>();
		Map<String, Integer> entryfreqs = new HashMap<String, Integer>();
		SeminarEntry seminar = null;
		String prefix = "";
		int doc_index = 0;
		
		//use the date2contents to process the seminars and talks information
		for(LinkedHashMap<Date, ArrayList<String>> doccontents : date2contents){
			Set<Date> date_keys = doccontents.keySet();
			LinkedHashMap<String, String> doclinks = all_links.get(doc_index++);
			
			ArrayList<SeminarEntry> seminars = new ArrayList<SeminarEntry>();
			
			HashMap<String, Integer> seen_people = new HashMap<String, Integer>();
			Set<String> likely_locations = new HashSet<String>();
			ArrayList<Integer> freqcounts = new ArrayList<Integer>();
			int tmpindex = 0;
			
			//preprocess the entries and get preliminary frequencies of titles
			for(Date date_key : date_keys){
				date_contents = doccontents.get(date_key);
				removenbsp(date_contents);
				tmpindex = getTitleIndex(date_contents, getSpeakers(date_contents, classifier));
				safeincrement(freqcounts, tmpindex);
				updatetitlefreqs(date_contents, entryfreqs);
				//System.out.println(getTitleIndex(date_contents, getSpeakers(date_contents, classifier)) + " " + date_contents.get(getTitleIndex(date_contents, getSpeakers(date_contents, classifier))));
			}
			//System.out.println(freqcounts);
			
			//sorts out IndexElements, based on title index frequencies
			ArrayList<IndexElement> elems = new ArrayList<IndexElement>();
			for(int i=0; i<freqcounts.size(); i++){
				elems.add(new IndexElement(i, freqcounts.get(i)));
			}
			Collections.sort(elems);
			elems = trimElems(elems);
			
			//create seminar talk objects
			for(Date date_key : date_keys){
				seminar = new SeminarEntry();
				seminar.setdate(date_key);
				
				date_contents = doccontents.get(date_key);
				ArrayList<String> tmpspeakers = getSpeakers(date_contents, classifier);
				
				//obtain speaker(s)
				if(tmpspeakers.size() > 0){
					seminar.speakers = tmpspeakers;
					for(String person : tmpspeakers){
						if(doclinks != null && doclinks.get(person) != null){ //add links
							seminar.addLink(doclinks.get(person));
						}
					}
				} else {
					for(String content : date_contents){
						content = content.replaceAll("\\s+", " ");
						tags = getTagValues(classifier.classifyWithInlineXML(content), TAG_PERSON_REGEX);

						for(String person : tags){
							if(seen_people.containsKey(person)){
								seen_people.put(person, seen_people.get(person) + 1);
							} else {
								seen_people.put(person, 1);
								int tmplen = person.split("\\s+").length;
								prefix = prevword(content, person);
								if((1 < tmplen || isprefix(prefix)) && tmplen < 5 && personconditions(person)){
									if(isprefix(prefix) && tmplen < 2) {
										seminar.addSpeaker(prefix + " " + person);
									} else {
										seminar.addSpeaker(person);
									}
									if(doclinks != null && doclinks.get(person) != null){ //add links
										seminar.addLink(doclinks.get(person));
									}
								}
							}
						}
					}	
				}
				
				//obtain title
				seminar.title = getTitle(date_contents, seminar.speakers, elems, entryfreqs);
				if(doclinks.get(seminar.title) != null){
					seminar.addLink(doclinks.get(seminar.title));
				}
				
				//record frequency of a person's name and put that name in a list if too frequent
				Set<String> people_key = seen_people.keySet();
				for(String person_key : people_key){
					if((int) seen_people.get(person_key) > 2 && person_key.contains(" Hall")){ //needs to have better heuristic here
						likely_locations.add(person_key);
					}
				}
				//remove_extraneous_people(date_contents, seminar); not working as intended
				if(seminar != null && seminar.date != null && seminar.speakers.size() > 0){
					seminars.add(seminar);
				}
			}
					
			//remove person's name if it occurs too many times
			for(String str : likely_locations) {
				remove_person(seminars, str);
			}
			
			//remove extraneous entries
			remove_invalid_talks(seminars);
			all_seminars.add(seminars);
		}
		
		//Finished, output the results to a location
		System.out.println("Finished processing.");
		writeseminars(all_seminars, urls);
		writeseminarscsv(all_seminars, urls);
		seminarsjson(all_seminars, urls);
		System.out.println("Complete.");
	}
}

class IndexElement implements Comparable<IndexElement>{

	int index;
	int freq;
	
	public IndexElement(int i, int f){
		index = i;
		freq = f;
	}
	
	public String toString(){
		return "(" + index + ", " + freq + ")";
	}
	
	@Override
	public int compareTo(IndexElement o) {
		// TODO Auto-generated method stub
		if(freq > o.freq){
			return -1;
		} else if (freq < o.freq){
			return 1;
		}
		return 0;
	}
	
}
