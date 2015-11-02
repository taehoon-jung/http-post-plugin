package jenkins.plugins.httppost;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

@SuppressWarnings("UnusedDeclaration") // This class will be loaded using its Descriptor.
public class HttpPostPublisher extends Notifier {

  private final String recipients;

  @DataBoundConstructor
  public HttpPostPublisher(String recipients) {
      this.recipients = recipients;
  }

  public String getRecipients() {
      return recipients;
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  @Override
  public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
    if (!build.getResult().isWorseOrEqualTo(Result.FAILURE)) {
      return true;
    }

    Descriptor descriptor = getDescriptor();
    String url = descriptor.url;
    String headers = descriptor.headers;
    if (url == null || url.length() == 0) {
      listener.getLogger().println("HTTP POST: No URL specified");
      return true;
    }

    try {
      OkHttpClient client = new OkHttpClient();
      client.setConnectTimeout(30, TimeUnit.SECONDS);
      client.setReadTimeout(60, TimeUnit.SECONDS);

      Request.Builder builder = new Request.Builder();
      builder.url(url);

      if (headers != null && headers.length() > 0) {
        String[] lines = headers.split("\r?\n");
        for (String line : lines) {
          int index = line.indexOf(':');
          builder.header(line.substring(0, index).trim(), line.substring(index + 1).trim());
        }
      }

      String content = String.format("Job failed: %s #%d\n%s",
                                     build.getProject().getName(),
                                     build.getNumber(),
                                     build.getAbsoluteUrl());

      JSONObject payload = new JSONObject();
      payload.put("serviceId", "ncs");
      payload.put("botNo", 9);
      payload.put("emailList", recipients.split(" "));
      payload.put("content", content);
      payload.put("type", 5);
      payload.put("push", 1);

      RequestBody body = RequestBody.create(
          MediaType.parse("application/json; charset=utf-8"),
          "payload=" + payload);

      builder.post(body);

      Request request = builder.build();
      listener.getLogger().println(String.format("---> POST %s", url));
      listener.getLogger().println(request.headers());

      long start = System.nanoTime();
      Response response = client.newCall(request).execute();
      long time = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      listener.getLogger()
          .println(String.format("<--- %s %s (%sms)", response.code(), response.message(), time));
      listener.getLogger().println(response.body().string());
    } catch (Exception e) {
      e.printStackTrace(listener.getLogger());
    }

    return true;
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  @Override
  public Descriptor getDescriptor() {
    return (Descriptor) super.getDescriptor();
  }

  @Extension
  public static final class Descriptor extends BuildStepDescriptor<Publisher> {

    public String url;
    public String headers;

    public Descriptor() {
      load();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
      return true;
    }

    @Override
    public String getDisplayName() {
      return "HTTP POST Publisher";
    }

    public FormValidation doCheckUrl(@QueryParameter String value) {
      if (value.length() == 0) {
        return FormValidation.error("URL must not be empty");
      }

      if (!value.startsWith("http://") && !value.startsWith("https://")) {
        return FormValidation.error("URL must start with http:// or https://");
      }

      try {
        new URL(value).toURI();
      } catch (Exception e) {
        return FormValidation.error(e.getMessage());
      }

      return FormValidation.ok();
    }

    public FormValidation doCheckHeaders(@QueryParameter String value) {
      if (value.length() > 0) {
        Headers.Builder headers = new Headers.Builder();
        String[] lines = value.split("\r?\n");

        for (String line : lines) {
          int index = line.indexOf(':');
          if (index == -1) {
            return FormValidation.error("Unexpected header: " + line);
          }

          try {
            headers.add(line.substring(0, index).trim(), line.substring(index + 1).trim());
          } catch (Exception e) {
            return FormValidation.error(e.getMessage());
          }
        }
      }

      return FormValidation.ok();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
      req.bindJSON(this, json.getJSONObject("http-post"));
      save();

      return true;
    }
  }
}
