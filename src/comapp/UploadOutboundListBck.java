package comapp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import javax.sql.DataSource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

@WebServlet("/UploadOutboundListBck")
@MultipartConfig(fileSizeThreshold = 1024 * 1024 * 150,
		maxFileSize = 1024 * 1024 * 150,
		maxRequestSize = 1024 * 1024 * 300)
public class UploadOutboundListBck extends HttpServlet {

	private static final long serialVersionUID = 1L;

	Logger log = Logger.getLogger("comapp." + this.getClass().getName());

	public UploadOutboundListBck() {
		super();
	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doPost(request, response);
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		HttpSession session = request.getSession();
		log.info(session.getId() + " - ******* new request ***** ");
		String action = request.getParameter("action");
		String environment = (String) session.getAttribute("Environment");
		Connection conn = null;
		CallableStatement cstmt = null;
		ResultSet rs = null;
		log.info(session.getId() + " action: " + action + " environment: " + environment);
		try {
			if (action == null) action = "";
			switch (action) {
			case "upload":
				Part filePart = request.getPart("uploadfile");
				String username = (String) session.getAttribute("UserName");
				Properties cs = ConfigServlet.getProperties();
				String upload_location = cs.getProperty("file-outbound-location");
				upload_location += File.separator + username;
				File _Dir_upload = new File(upload_location);
				if (!_Dir_upload.exists()) {
					_Dir_upload.mkdirs();
				}
				log.info(session.getId() + " - username : " + username + " - file-outbound-location : " + upload_location);
				SimpleDateFormat sdf_dest = new SimpleDateFormat("yyyyMMddhhmmss");
				int nRecordLetti = 0;
				int nRecordInseriti = 0;
				int nRecordScartati = 0;
				try {
					Context ctx = new InitialContext();
					DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/" + ConfigServlet.web_app + "OCS");
					log.info(session.getId() + " - connection OCS wait...");
					conn = ds.getConnection();

					log.info(session.getId() + " - Upload : inizio upload");
					if (filePart != null) {
						String file_name = filePart.getSubmittedFileName();
						upload_location += File.separator + sdf_dest.format(new Date()) + "_" + file_name;
						InputStream is = filePart.getInputStream();
						FileOutputStream os = new FileOutputStream(new File(upload_location));
						IOUtils.copy(is, os);
						is.close();
						os.close();
					}
					log.info(session.getId() + " - Upload : File salvato in : " + upload_location);
					log.info(session.getId() + " - Upload : fine upload");

					long startTime = System.currentTimeMillis();
					log.info(session.getId() + " - File : inizio caricamento csv in memoria");
					List<String[]> records = new ArrayList<>();
					try (BufferedReader br = new BufferedReader(new FileReader(upload_location))) {
						String line;
						boolean firstLine = true;
						while ((line = br.readLine()) != null) {
							if (StringUtils.isBlank(line)) continue;
							String[] record = line.split(";", -1);
							if (firstLine) {
								firstLine = false;
								if (record.length > 0 && "contact_info".equalsIgnoreCase(StringUtils.trim(record[0]))) {
									continue;
								}
							}
							if (record.length == 4 || record.length == 5 || record.length == 27) {
								records.add(record);
							} else if (record.length > 4) {
								records.add(record);
							} else {
								nRecordScartati++;
								log.warn(session.getId() + " - riga scartata (numero colonne=" + record.length + "): " + line);
							}
						}
					} catch (Exception ex) {
						log.error(session.getId() + " - errore lettura CSV: " + ex.getMessage(), ex);
					}
					nRecordLetti = records.size();
					log.info(session.getId() + " - File : numero record letti: " + nRecordLetti
							+ " - scartati: " + nRecordScartati);
					log.info(session.getId() + " - File : fine caricamento csv in memoria");
					long endTime = System.currentTimeMillis();
					long totalTime = endTime - startTime;
					log.info(session.getId() + " - DB : Caricato in " + totalTime + " ms");

					startTime = System.currentTimeMillis();
					log.info(session.getId() + " - DB : inizio inserimento record");
					for (String[] r : records) {
						String contact_info = (r.length > 0) ? StringUtils.trim(r[0]) : "";
						String nome_cognome = (r.length > 1) ? StringUtils.trim(r[1]) : "";
						String numero_act = (r.length > 2) ? StringUtils.trim(r[2]) : "";
						String processo = (r.length > 3) ? StringUtils.trim(r[3]) : "";
						log.info(session.getId() + " - dashboard.OutboundListBck_InsertCallingList('"
								+ contact_info + "','" + nome_cognome + "','" + numero_act + "','" + processo + "')");
						try {
							cstmt = conn.prepareCall("{ call dashboard.OutboundListBck_InsertCallingList(?,?,?,?)} ");
							cstmt.setString(1, contact_info);
							cstmt.setString(2, nome_cognome);
							cstmt.setString(3, numero_act);
							cstmt.setString(4, processo);
							cstmt.execute();
							log.debug(session.getId() + " - executeCall complete");
							nRecordInseriti++;
						} catch (Exception ex) {
							log.error(session.getId() + " - errore inserimento record: " + ex.getMessage(), ex);
						} finally {
							try { cstmt.close(); } catch (Exception e) {}
						}
					}
					endTime = System.currentTimeMillis();
					totalTime = endTime - startTime;
					log.info(session.getId() + " - DB : Inserimento in " + totalTime + " ms");
					log.info(session.getId() + " - DB : fine inserimento - inseriti: " + nRecordInseriti);
					JSONObject obj = new JSONObject();
					obj.put("res", "OK");
					obj.put("read", nRecordLetti);
					obj.put("inserted", nRecordInseriti);
					obj.put("skipped", nRecordScartati);
					String re = obj.toString();
					log.info(session.getId() + " - File Uploaded Successfully ->" + re);
					response.getOutputStream().print(re);
				} catch (Exception e) {
					log.error(session.getId() + " : " + e.getMessage(), e);
					JSONObject obj = new JSONObject();
					try {
						obj.put("res", "KO");
						obj.put("errcode", "1");
						obj.put("err", e.getMessage());
					} catch (JSONException e1) {}
					String re = obj.toString();
					log.error(session.getId() + " - File Uploaded Error ->" + re);
					response.getOutputStream().print(re);
				} finally {
					try { rs.close(); } catch (Exception e) {}
					try { cstmt.close(); } catch (Exception e) {}
					try { conn.close(); } catch (Exception e) {}
				}
				break;
			default:
				log.warn(session.getId() + " - azione non gestita: " + action);
				break;
			}
		} catch (Exception e) {
			log.error(session.getId() + " : " + e.getMessage(), e);
			JSONObject obj = new JSONObject();
			try {
				obj.put("res", "KO");
				obj.put("errcode", "1");
				obj.put("err", e.getMessage());
			} catch (JSONException e1) {}
			String re = obj.toString();
			log.error(session.getId() + " - Servlet Error ->" + re);
			response.getOutputStream().print(re);
		}
	}
}
