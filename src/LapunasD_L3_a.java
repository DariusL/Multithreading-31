import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.jcsp.lang.Alternative;
import org.jcsp.lang.CSProcess;
import org.jcsp.lang.Channel;
import org.jcsp.lang.ChannelInput;
import org.jcsp.lang.ChannelInputInt;
import org.jcsp.lang.ChannelOutput;
import org.jcsp.lang.ChannelOutputInt;
import org.jcsp.lang.Guard;
import org.jcsp.lang.One2OneChannel;
import org.jcsp.lang.One2OneChannelInt;
import org.jcsp.lang.Parallel;
import org.jcsp.lang.PoisonException;


public class LapunasD_L3_a {
    public static final String DELIMS = "[ ]+";
	
	//pagrindine duomenu struktura
	static class Struct{
		public String pav;
		public int kiekis;
		public double kaina;
		
		Struct(String input){
			String stuff[] = input.split(DELIMS);
			pav = stuff[0];
			kiekis = Integer.valueOf(stuff[1]);
            kaina = Double.valueOf(stuff[2]);
		}
		
		@Override
        public String toString() {
            return String.format("%16s %7d %10f", pav, kiekis, kaina);
        }
	}
	
	//skaitiklis naudojamas vartojimui
	static class Counter implements Comparable<Counter>{
		public String pav;
		public int count;
		
		Counter(){
			this("", 0);
		}
		
		Counter(String pav, int count){
			this.pav = pav;
			this.count = count;
		}
		
		Counter(String input){
			String stuff[] = input.split(DELIMS);
			pav = stuff[0];
			count = Integer.valueOf(stuff[1]);
		}

		@Override
		public int compareTo(Counter o) {
			return pav.compareTo(o.pav);
		}
		
		@Override
		public String toString() {
			return String.format("%15s %5d", pav, count);
		}
	}
	
	//gamintojas
	static class Producer implements CSProcess{
		private ArrayList<Struct> data;//ka jis gamina
		private ChannelOutput writeChannel;//isvedimo kanalas
		
		public Producer(ArrayList<Struct> data, ChannelOutput writeChanel){
			this.data = data;
			this.writeChannel = writeChanel;
		}

		@Override
		public void run() {
			try{
				for(Struct c : data){
					writeChannel.write(new Counter(c.pav, c.kiekis));
				}
				writeChannel.poison(500);
			}catch(PoisonException e){
				//sprogom
			}
		}
	}
	
	//vartotojas
	static class Consumer implements CSProcess{
		private ArrayList<Counter> requirements;//ka reikia suvartoti
		private ArrayList<Counter> deficit;//ko nepavyko suvartoti
		private ChannelOutput requests;//kanalas uzklausoms siusti
		private ChannelInputInt results;//kanalas atsakymams gauti
		
		public Consumer(ArrayList<Counter> requirements, ChannelOutput requests, ChannelInputInt results) {
			deficit = new ArrayList<LapunasD_L3_a.Counter>();
			this.requirements = requirements;
			this.requests = requests;
			this.results = results;
		}

		@Override
		public void run() {
			int i = 0;
			Counter counter;
			try{
				while(requirements.size() > 0){
					i++;
					i %= requirements.size();
					
					counter = requirements.get(i);//ko si karta prasysim
					requests.write(counter);
					int taken =  results.read();//kiek pavyko suvartoti
					counter.count -= taken;

					if(counter.count <= 0){
						requirements.remove(i);
					}
				}
				requests.poison(500);
			}catch(PoisonException e){
				deficit.addAll(requirements);//jei mus pabaige valdytojas, nieko nebegausim
				requests.poison(500);
			}
			for(Counter def : deficit){
				System.out.println(def); 
			}
		}
	}
	
	//valdytojas
	static class Buffer implements CSProcess{
		private ArrayList<Counter> data = new ArrayList<>();//buferis
		
		private ArrayList<ChannelOutputInt> consumerResults;//kanalai siusti atsakymus vartotojams
		
		//kanalai ir pasirinkimai gauti uzklausas
		private Alternative consumerRequestAlt;
		private ChannelInput consumerRequests[];
		
		//kanalai ir pasirinkimai gauti apdoroti gamyba
		private Alternative productionAlt;
		private ChannelInput production[];
		
		//informacija apie veikiancius vartotojus/gamintojus
		private boolean availableProducers[];
		private boolean availableConsumers[];
		private boolean hasConsumers = true;
		private boolean hasProducers = true;
		
		public Buffer(ArrayList<ChannelInput> consumerRequests, 
				ArrayList<ChannelInput> production, ArrayList<ChannelOutputInt> consumerResults){
			//nieko labai idomaus
			this.consumerRequests = new ChannelInput[consumerRequests.size()];
			consumerRequests.toArray(this.consumerRequests);
			this.consumerRequestAlt = new Alternative(Arrays.copyOf(this.consumerRequests, this.consumerRequests.length, Guard[].class));
			this.consumerResults = consumerResults;
			this.production = new ChannelInput[production.size()]; 
			production.toArray(this.production);
			this.productionAlt = new Alternative(Arrays.copyOf(this.production, this.production.length, Guard[].class));
			availableConsumers = new boolean[consumerRequests.size()];
			Arrays.fill(availableConsumers, true);
			availableProducers = new boolean[production.size()];
			Arrays.fill(availableProducers, true);
		}
		
		//naujo elemento pridejimas
		private void add(Counter counter){
			int found = Collections.binarySearch(data, counter);
			if(found >= 0){
				data.get(found).count += counter.count;
			}else{
				data.add(-(found + 1), counter);
			}
		}
		
		//vartojimas
		private int take(Counter req){
			int taken = 0;
			int found = Collections.binarySearch(data, req);
			if(found >= 0){
				Counter counter = data.get(found);
				if(counter.count >= req.count)
					taken = req.count;
				else
					taken = counter.count;
				
				counter.count -= taken;
				
				if(counter.count <= 0)
					data.remove(found);
			}
			return taken;
		}

		@Override
		public void run() {
			while(hasProducers || hasConsumers){
				if(data.size() > 0){
					if(hasConsumers){
						int c = consumerRequestAlt.fairSelect(availableConsumers);//issirenkam aprodojimui vartotoja
						try{
							Counter req = (Counter) consumerRequests[c].read();
							int taken = take(req);
							if(taken == 0 && !hasProducers)
								consumerResults.get(c).poison(500);//nutraukiam vartojima jei nerado nieko gero ir nebera gamintoju
							else
								consumerResults.get(c).write(taken);
						}catch(PoisonException e){
							availableConsumers[c] = false;//pazymim vartotoja kaip baigusi darba
							hasConsumers = hasTrue(availableConsumers);
						}
					}
				}
				
				if(hasProducers){
					int p = productionAlt.fairSelect(availableProducers);//issirenkam gamintoja
					try{
						Counter in = (Counter) production[p].read();
						add(in);
						
					}catch(PoisonException e){
						availableProducers[p] = false;
						hasProducers = hasTrue(availableProducers);
					}
				}else if(data.size() == 0){
					//jei nebera gamintoju ir duomenys baiges, stabdom vartojima
					for(ChannelInput req : consumerRequests){
						req.poison(500);
					}
					Arrays.fill(availableConsumers, false);
					hasConsumers = false;
				}
			}
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			for(Counter c : data)
				builder.append(c);
			return builder.toString();
		}
	}
	
	public static boolean hasTrue(boolean[] arr){
		for(boolean b : arr){
			if(b){
				return true;
			}
		}
		return false;
	}
	
	public static ArrayList<String> readLines(String filename) throws Exception{
        FileReader fileReader = new FileReader(filename);
        BufferedReader bufferedReader = new BufferedReader(fileReader);
        ArrayList<String> lines = new ArrayList<>();
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            lines.add(line);
        }
        bufferedReader.close();
        return lines;
    }
	
	public static ArrayList<ArrayList<Struct>> producers(String failas) throws Exception{
    	ArrayList<ArrayList<Struct>> ret = new ArrayList<>();
        ArrayList<String> duomenai = readLines(failas);
        ArrayList<Struct> tmp = new ArrayList<>();
        loop:
        for(String line : duomenai){
            switch (line) {
			case "":
				ret.add(tmp);
				tmp = new ArrayList<>();
				break;
			case "vartotojai":
				break loop;
			default:
				tmp.add(new Struct(line));
				break;
			}
        }
        return ret;
    }
	
	public static ArrayList<ArrayList<Counter>> consumers(String failas) throws Exception{
		ArrayList<ArrayList<Counter>> ret = new ArrayList<>();
		ArrayList<Counter> tmp = new ArrayList<>();
		ArrayList<String> lines = readLines(failas);
		int i;
		for(i = 0; i < lines.size(); i++){
			if(lines.get(i).equals("vartotojai")){
				break;
			}
		}
		for(i++; i < lines.size(); i++){
			if("".equals(lines.get(i))){
				ret.add(tmp);
				tmp = new ArrayList<>();
			}else{
				tmp.add(new Counter(lines.get(i)));
			}
		}
		ret.add(tmp);
		return ret;
	}
	
	public static void antraste(){
    	System.out.printf("%10s %2s %15s %7s %10s\n", "Procesas", "Nr", "Pavadinimas", "Kiekis", "Kaina");
    }
	
	public static void spausdinti(ArrayList<Struct> duomenai, String prefix){
        for(int i = 0; i < duomenai.size(); i++)
            System.out.println(prefix + i + " " + duomenai.get(i).toString());
    }
	
	public static void spausdinti(ArrayList<Counter> duomenai, int nr){
		System.out.println("Varotojas_" + nr);
        for(int i = 0; i < duomenai.size(); i++)
            System.out.println(duomenai.get(i));
    }

	public static void main(String[] args) throws Exception {
		ArrayList<ArrayList<Struct>> pdata = producers("LapunasD_L3.txt");
		ArrayList<ArrayList<Counter>> cdata = consumers("LapunasD_L3.txt");
		
		System.out.print("\nGamintojai\n\n");
		antraste();
		for(int i = 0; i < pdata.size(); i++)
        	spausdinti(pdata.get(i), "Procesas_" + i + " ");
		System.out.print("\nVartotojai\n\n");
		for(int i = 0; i < cdata.size(); i++)
			spausdinti(cdata.get(i), i);
		System.out.print("\nVartotojams truko:\n");
		
		ArrayList<CSProcess> threads = new ArrayList<>();//visos gijos
		ArrayList<ChannelInput> production = new ArrayList<>();//gamybos kanalai valdytojui
		ArrayList<ChannelInput> consumerRequests = new ArrayList<>();//vartotoju uzklausu kanalai valdytojui
		ArrayList<ChannelOutputInt> consumerResults = new ArrayList<>();//vartotoju atsakymu kanalai valdytojui
		
		for(ArrayList<Counter> consumer : cdata){
			//sukuriam vartotoja ir 2 kanalus
			One2OneChannel request = Channel.one2one(0);
			One2OneChannelInt result = Channel.one2oneInt(0);
			
			threads.add(new Consumer(consumer, request.out(), result.in()));
			consumerRequests.add(request.in());
			consumerResults.add(result.out());
		}
		
		for(ArrayList<Struct> producer : pdata){
			//sukuriam gamintoja ir kanala
			One2OneChannel channel = Channel.one2one(0);
			
			threads.add(new Producer(producer, channel.out()));
			production.add(channel.in());			
		}
		
		//sukuriam valdytoja
		Buffer buffer = new Buffer(consumerRequests, production, consumerResults);
		
		threads.add(buffer);
		//apleidziame visas gijas
		new Parallel(threads.toArray(new CSProcess[0])).run();
		
		System.out.print("\nNesuvartota liko:\n");
		System.out.print(buffer);
	}

}
