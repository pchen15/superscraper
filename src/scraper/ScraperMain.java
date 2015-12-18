package scraper;
import java.io.FileOutputStream;
import java.io.PrintStream;
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
		setBannedWords(); //sets words that are unlikely to occur in names and titles

		//redirect error messages away from console
		PrintStream out = new PrintStream(new FileOutputStream("error-log.txt"));
		System.setErr(out);
		
		//obtaining input
		Scanner input = new Scanner(System.in);
		ArrayList<String> inputurls = new ArrayList<String>();
		System.out.println("Input seminar URLs, then press enter twice:");
		String nexturl = "";
		while(true){
			nexturl = input.nextLine();
			if(nexturl.equals("")){
				break;
			}
			inputurls.add(nexturl);
		}
		String[] urls = inputurls.toArray(new String[inputurls.size()]);
		input.close();
		
		//connect to the urls provided, obtain a list of all entries, and fetch embedded hyperlinks as well
		ArrayList<String> originalcontents = createfilecontents(urls);
		ArrayList<LinkedHashMap<String, String>> all_links = new ArrayList<LinkedHashMap<String, String>>();
		System.out.println("Fetching hyperlinks...");
		all_links = getlinks(urls);
		System.out.print("\r100%\nFinished fetching links.\n");
		
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
		System.out.println("Extracting talk information...");
		int tmpi = 0;
		for(String htmlcontent : originalcontents){
			System.out.print("\r" + (50*tmpi++)/originalcontents.size() + "%");

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
		}

		//set up classifiers and more variables for title frequencies
		String serializedClassifier = "classifiers/english.all.3class.distsim.crf.ser.gz";
		AbstractSequenceClassifier<CoreLabel> classifier;
		try{
			classifier = CRFClassifier.getClassifierNoExceptions(serializedClassifier);
		} catch (Exception e){
			System.out.println("\nMissing " + serializedClassifier);
			return;
		}

		ArrayList<ArrayList<SeminarTalk>> all_seminars = new ArrayList<ArrayList<SeminarTalk>>();
		ArrayList<String> date_contents = new ArrayList<String>();
		Set<String> tags = new HashSet<String>();
		Map<String, Integer> entryfreqs = new HashMap<String, Integer>();
		SeminarTalk seminartalk = null;
		String prefix = "";
		int doc_index = 0;
		
		//use the date2contents to process the seminars and talks information
		for(LinkedHashMap<Date, ArrayList<String>> doccontents : date2contents){
			System.out.print("\r" + (50 + ((50*doc_index)/date2contents.size())) + "%");

			Set<Date> date_keys = doccontents.keySet();
			LinkedHashMap<String, String> doclinks = all_links.get(doc_index++);
			
			ArrayList<SeminarTalk> talks = new ArrayList<SeminarTalk>();
			
			HashMap<String, Integer> seen_people = new HashMap<String, Integer>();
			Set<String> likely_locations = new HashSet<String>();
			ArrayList<Integer> freqcounts = new ArrayList<Integer>();
			int tmpindex = 0;
			
			//preprocess the entries and get preliminary frequencies of titles
			for(Date date_key : date_keys){
				date_contents = doccontents.get(date_key);
				removenbsp(date_contents);
				tmpindex = getTitleIndex(date_contents, getSpeakers(date_contents, classifier));
				if(tmpindex > -1) safeincrement(freqcounts, tmpindex);
				//System.out.println(date_contents.get(tmpindex) + " " + tmpindex);
				updatetitlefreqs(date_contents, entryfreqs);
			}
			
			//sort out IndexElements, based on title index frequencies
			ArrayList<IndexElement> elems = new ArrayList<IndexElement>();
			for(int i=0; i<freqcounts.size(); i++){
				elems.add(new IndexElement(i, freqcounts.get(i)));
			}
			Collections.sort(elems);
			elems = trimElems(elems);
			
			//create seminar talk objects
			for(Date date_key : date_keys){
				seminartalk = new SeminarTalk();
				seminartalk.setdate(date_key);
				
				date_contents = doccontents.get(date_key);
				ArrayList<String> tmpspeakers = getSpeakers(date_contents, classifier);
				
				//obtain speaker(s)
				if(tmpspeakers.size() > 0){
					seminartalk.speakers = tmpspeakers;
					for(String person : tmpspeakers){
						if(doclinks != null && doclinks.get(person) != null){ //add links
							seminartalk.addLink(doclinks.get(person));
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
										seminartalk.addSpeaker(prefix + " " + person);
									} else {
										seminartalk.addSpeaker(person);
									}
									if(doclinks != null && doclinks.get(person) != null){ //add links
										seminartalk.addLink(doclinks.get(person));
									}
								}
							}
						}
					}	
				}
				
				//obtain title
				seminartalk.title = getTitle(date_contents, seminartalk.speakers, elems, entryfreqs);
				if(doclinks.get(seminartalk.title) != null){
					seminartalk.addLink(doclinks.get(seminartalk.title));
				}
				
				//record frequency of a person's name and put that name in a list if too frequent
				Set<String> people_key = seen_people.keySet();
				for(String person_key : people_key){
					if((int) seen_people.get(person_key) > 2 && person_key.contains(" Hall")){ //needs to have better heuristic here
						likely_locations.add(person_key);
					}
				}
				//remove_extraneous_people(date_contents, seminar); not working as intended
				if(seminartalk != null && seminartalk.date != null && seminartalk.speakers.size() > 0){
					talks.add(seminartalk);
				}
			}
					
			//remove person's name if it occurs too many times
			for(String str : likely_locations) {
				remove_person(talks, str);
			}
			
			//remove extraneous entries and cleans all titles if most titles are blank
			remove_invalid_talks(talks);
			cleartitlesmaj(talks);
			all_seminars.add(talks);
		}
		
		//Finished, output the results in the same directory
		System.out.print("\r100%\nFinished extraction.\n");
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
