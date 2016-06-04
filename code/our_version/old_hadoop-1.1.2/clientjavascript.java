import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.FileWriter;

class clientjavascript {
	public static void main(String[] args) {
		/* scan the given directory */
		File mauidir = new File("/mnt/mauifs/1TB_directory_x");	
		try
		{
			scanMauifs(mauidir);	
		}
		catch (IOException ex)
		{
			//handle the exception
		}
	}

	private static void scanMauifs(File dir) throws IOException{
		/*remove the current global file info*/
		Runtime.getRuntime().exec("rm /mnt/mauifs/global_file_info");

		File filesInDir[] = dir.listFiles();
		FileWriter file_writer = new FileWriter("/mnt/mauifs/global_file_info", true);
		PrintWriter writer = new PrintWriter(file_writer);

		if (filesInDir != null) {
			for (File f : filesInDir) {
				if (f.isFile())
                		{
                                	Process proc = Runtime.getRuntime().exec("mauiobjbrowser -t a23f51772832433a99b8c8dd6a5adc27 -p " + f.getPath().replace("/mnt/mauifs", ""));
                                	BufferedReader read = new BufferedReader(new InputStreamReader(proc.getInputStream()));

                                	String final_block_info = "";
                                	String replica_id = "";
					String device_name = "";
					String final_osdname = "";

                                	while (read.read() != -1)
                                	{
                                        	String output = read.readLine();

						if (output.contains("replica id:"))
                                        	{
							final_block_info = final_block_info + f + " ";
                                                	replica_id = output.replace("replica id:\t", "");
							final_block_info = final_block_info + replica_id;
                                        	}

						if (output.contains("SS:"))
                                        	{
							device_name = output.replace("\tSS:\t\t", "");
							final_block_info = final_block_info + " " + device_name;
                                        	}

                                                if (output.contains("OSD"))
                                                {
							if (final_osdname.equals(""))
							{
                                                       		String osdname = output.replace("\tOSD ID (hex):\t", "");
								String dir_first =  osdname.substring(0, 4);
								String dir_second = osdname.substring(4, 7);
								String dir_third = osdname.substring(7, 10);
								String dir_fourth = osdname.substring(10, 13);
								String dir_fifth = osdname.substring(13, 16);
								final_osdname = final_osdname + replica_id + Integer.toString(Integer.parseInt(dir_first, 16)) + Integer.toString(Integer.parseInt(dir_second, 16)) + Integer.toString(Integer.parseInt(dir_third, 16)) + Integer.toString(Integer.parseInt(dir_fourth, 16)) + Integer.toString(Integer.parseInt(dir_fifth, 16));
							}
							final_block_info = final_block_info + " " + final_osdname;
						}

                                                if (output.contains("Path"))
                                                {
                                                        String path_to_link = output.replace("\tPath:\t\t", "");

                                                       	final_block_info = final_block_info + " " +  path_to_link + "\n";
                                                	writer.append(final_block_info);
                                                	final_block_info = "";
                                       		}
					}
				}
				else
				{
					scanMauifs(f);
				}
			}
		}
		writer.close();
	}
}
