package com.focusit.jsflight.player.scenario;

import com.focusit.jsflight.player.configurations.CommonConfiguration;
import com.focusit.jsflight.player.configurations.ScriptsConfiguration;
import com.focusit.jsflight.player.constants.BrowserType;
import com.focusit.jsflight.player.constants.EventConstants;
import com.focusit.jsflight.player.constants.EventType;
import com.focusit.jsflight.player.handler.*;
import com.focusit.jsflight.player.iframe.FrameSwitcher;
import com.focusit.jsflight.player.script.PlayerScriptProcessor;
import com.focusit.jsflight.player.webdriver.SeleniumDriver;
import com.focusit.jsflight.script.constants.ScriptBindingConstants;
import com.focusit.jsflight.script.player.PlayerContext;
import org.json.JSONObject;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Class that really replays an event in given scenario and given selenium driver
 * Created by doki on 05.05.16.
 */
public class ScenarioProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(ScenarioProcessor.class);

    protected UserScenario scenario;
    protected SeleniumDriver seleniumDriver;

    private WebDriver getWebDriver(JSONObject event)
    {
        BrowserType browserType = scenario.getConfiguration().getCommonConfiguration().getBrowserType();
        String path = scenario.getConfiguration().getCommonConfiguration().getPathToBrowserExecutable();
        String proxyHost = scenario.getConfiguration().getCommonConfiguration().getProxyHost();
        Integer proxyPort = scenario.getConfiguration().getCommonConfiguration().getProxyPort();

        return seleniumDriver.getDriverForEvent(event, browserType, path, proxyHost, proxyPort);
    }

    /**
     * Method that check if a browser has error dialog visible.
     * and if it has then throws an exception.
     * A browser after any step should not contain any error
     *
     * @param webDriver Selenium WebDriver associated with current scenario
     */
    protected void throwIfBrowserHaveAnError(WebDriver webDriver) throws Exception
    {
        String findBrowserErrorScript = scenario.getConfiguration().getScriptsConfiguration()
                .getIsBrowserHaveErrorScript();
        Map<String, Object> binding = PlayerScriptProcessor.getEmptyBindingsMap();
        binding.put(ScriptBindingConstants.WEB_DRIVER, webDriver);
        boolean pageContainsError = new PlayerScriptProcessor(scenario.getContext()).executeGroovyScript(findBrowserErrorScript, binding, Boolean.class);
        if (pageContainsError)
        {
            throw new IllegalStateException("Browser contains some error after step processing");
        }
    }

    /**
     * This method will decide whether step processing should be terminated at current step or not.
     * Or, in other words, should an exception be there or not.
     * Default implementation just logs
     *
     * @param position
     * @param ex
     * @throws Exception
     */
    protected void processClickException(int position, Exception ex) throws Exception
    {
        LOG.error("Failed to process step: " + position, ex);
    }

    /**
     * Make a screenshot and save to a file
     *
     * @param theWebDriver
     * @param position
     */
    protected void makeAShot(WebDriver theWebDriver, int position, boolean isError)
    {
        if (scenario.getConfiguration().getCommonConfiguration().getMakeShots())
        {
            LOG.info("Making screenshot");
            String screenDir = scenario.getConfiguration().getCommonConfiguration().getScreenshotsDirectory();
            File dir = new File(screenDir, Paths.get(scenario.getScenarioFilename()).getFileName().toString());

            if (!dir.exists() && !dir.mkdirs())
            {
                return;
            }
            String errorPart = isError ? "_error_" : "";
            File file = Paths.get(dir.getAbsolutePath(), errorPart + String.format("%05d", position) + ".png").toFile();
            try (FileOutputStream fos = new FileOutputStream(file))
            {
                seleniumDriver.makeAShot(theWebDriver, fos);
            }
            catch (IOException e)
            {
                LOG.error(e.toString(), e);
            }
        }
    }

    public void applyStep(int position)
    {
        JSONObject event = scenario.getStepAt(position);
        PlayerContext context = scenario.getContext();
        context.setCurrentScenarioStep(event);

        ScriptsConfiguration scriptsConfiguration = scenario.getConfiguration().getScriptsConfiguration();
        PlayerScriptProcessor scriptProcessor = new PlayerScriptProcessor(context);
        String eventUrl = scriptProcessor.executeUrlReplacementScript(
                scriptsConfiguration.getUrlReplacementScript(), event, scenario);
        event.put(EventConstants.URL, eventUrl);

        String type = event.getString(EventConstants.TYPE);
        LOG.info("Event type: {}", type);

        scriptProcessor.runStepPrePostScript(event, position, true, scenario);
        event = scriptProcessor.runStepTemplating(scenario, event);

        // there must be update for eventUrl after templating!
        eventUrl = event.getString(EventConstants.URL);

        //if template processing fails for URL we cannot process this step, so we skip
        if (eventUrl.matches(".*(\\$\\{.*\\}).*"))
        {
            LOG.error("Event at position {} cannot be processed due to url contains unprocessed templates\n"
                    + "EventId: {}\n" + "URL: {}", position, event.get(EventConstants.EVENT_ID), eventUrl);
            return;
        }

        LOG.info("Current step eventId " + event.get(EventConstants.EVENT_ID).toString() + " URL: {}",
                event.get(EventConstants.URL));

        WebDriver webDriver = null;
        boolean error = false;
        CommonConfiguration commonConfiguration = scenario.getConfiguration().getCommonConfiguration();
        try
        {
            if (scenario.isStepDuplicates(scriptsConfiguration.getDuplicationHandlerScript(), event))
            {
                LOG.warn("Event duplicates previous");
                return;
            }

            if (UserScenario.isEventIgnored(event) || UserScenario.isEventBad(event))
            {
                StringBuilder builder = new StringBuilder();
                if (event.has(EventConstants.TARGET))
                {
                    builder.append(" Target: '");
                    builder.append(event.get(EventConstants.TARGET));
                    builder.append("';");
                }

                if (event.has(EventConstants.FIRST_TARGET))
                {
                    builder.append(" First Target: '");
                    builder.append(event.get(EventConstants.FIRST_TARGET));
                    builder.append("';");
                }

                if (event.has(EventConstants.SECOND_TARGET))
                {
                    builder.append(" Second Target: '");
                    builder.append(event.get(EventConstants.SECOND_TARGET));
                    builder.append("';");
                }

                LOG.warn("Event is ignored or bad. Type: " + event.get(EventConstants.TYPE) + builder.toString());
                return;
            }

            if (type.equalsIgnoreCase(EventType.SCRIPT))
            {
                scriptProcessor.executeScriptEvent(
                        scriptsConfiguration.getScriptEventHandlerScript(), event);
                return;
            }

            //Configure webdriver for this event, setting params here so we can change parameters while playback is
            //paused
            seleniumDriver
                    .setAsyncRequestsCompletedTimeoutInSeconds(
                            commonConfiguration.getAsyncRequestsCompletedTimeoutInSeconds())
                    .setIsAsyncRequestsCompletedScript(scriptsConfiguration.getIsAsyncRequestsCompletedScript())
                    .setMaxElementGroovy(commonConfiguration.getMaxElementGroovy())
                    .setElementLookupScript(scriptsConfiguration.getElementLookupScript())
                    .setIsUiShownScript(scriptsConfiguration.getIsUiShownScript())
                    .setUseRandomStringGenerator(commonConfiguration.isUseRandomChars())
                    .setIntervalBetweenUiChecksInMs(commonConfiguration.getIntervalBetweenUiChecksMs())
                    .setUiShownTimeoutInSeconds(commonConfiguration.getUiShownTimeoutSeconds())
                    .setPlaceholders(scenario.getConfiguration().getWebConfiguration().getPlaceholders())
                    .setSelectXpath(scenario.getConfiguration().getWebConfiguration().getSelectXpath())
                    .setIsSelectElementScript(scriptsConfiguration.getIsSelectElementScript())
                    .setSendSignalToProcessScript(scriptsConfiguration.getSendSignalToProcessScript())
                    .setSkipKeyboardScript(scriptsConfiguration.getShouldSkipKeyboardScript())
                    .setGetWebDriverPidScript(scriptsConfiguration.getGetWebDriverPidScript())
                    .setKeepBrowserXpath(commonConfiguration.getFormOrDialogXpath());

            webDriver = getWebDriver(event);
            if (webDriver == null)
            {
                throw new NullPointerException("getWebDriver return null");
            }
            seleniumDriver.openEventUrl(webDriver, event);

            LOG.info("Event {}. Display {}", position, seleniumDriver.getDriverDisplay(webDriver));

            seleniumDriver.waitWhileAsyncRequestsWillCompletedWithRefresh(webDriver, event);

            FrameSwitcher.switchToWorkingFrame(webDriver, event);

            try
            {
                String target = UserScenario.getTargetForEvent(event);

                BaseEventHandler eventHandler = new DummyEventHandler();
                switch (type)
                {
                case EventType.MOUSE_WHEEL:
                    eventHandler = new MouseWheelEventHandler();
                    break;
                case EventType.SCROLL_EMULATION:
                    seleniumDriver.processScroll(webDriver, event, target);
                    break;
                case EventType.MOUSE_DOWN:
                case EventType.CLICK:
                    eventHandler = new MouseClickEventHandler();
                    eventHandler.addAdditionalProperty(MouseClickEventHandler.IS_SELECT_ELEMENT_SCRIPT,
                            scriptsConfiguration.getIsSelectElementScript());
                    eventHandler.addAdditionalProperty(MouseClickEventHandler.SELECT_XPATH, commonConfiguration.getFormOrDialogXpath());
                    break;
                case EventType.KEY_UP:
                case EventType.KEY_DOWN:
                    eventHandler = new KeyUpDownEventHandler();
                    eventHandler.addAdditionalProperty(KeyUpDownEventHandler.SHOULD_SKIP_KEYBOARD_SCRIPT,
                            scriptsConfiguration.getShouldSkipKeyboardScript());
                    break;
                case EventType.KEY_PRESS:
                    eventHandler = new KeyPressEventHandler();
                    eventHandler.addAdditionalProperty(KeyPressEventHandler.SHOULD_SKIP_KEYBOARD_SCRIPT,
                            scriptsConfiguration.getShouldSkipKeyboardScript());
                    eventHandler.addAdditionalProperty(KeyPressEventHandler.USE_RANDOM_CHARS,
                            String.valueOf(commonConfiguration.isUseRandomChars()));
                    break;
                default:
                    LOG.warn("Unknown event type: {}", type);
                    break;
                }

                eventHandler.handleEvent(webDriver, event);

                seleniumDriver.waitWhileAsyncRequestsWillCompletedWithRefresh(webDriver, event);
                new PlayerScriptProcessor(context).doWaitAfterEvent(seleniumDriver, webDriver, event, scenario);

                throwIfBrowserHaveAnError(webDriver);
            }
            catch (Exception e)
            {
                processClickException(position, e);
            }
        }
        catch (Exception e)
        {
            error = true;
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
        finally
        {
            //webdriver can stay null if event is ignored or bad, thus can`t be postprocessed
            if (webDriver != null)
            {
                if (!error)
                {
                    scenario.updateEvent(event);
                    scriptProcessor.runStepPrePostScript(event, position, false, scenario);
                }
                makeAShot(webDriver, position, error);
                seleniumDriver.releaseBrowser(webDriver, event);
            }
            else
            {
                LOG.warn("Unable to make screenshot, because web driver is null");
            }
        }
    }

    public ScenarioProcessor(UserScenario scenario, SeleniumDriver seleniumDriver)
    {
        scenario = scenario;
        seleniumDriver = seleniumDriver;
    }

    public void play()
    {
        play(0, 0);
    }

    public void play(int start, int finish)
    {
        long begin = System.currentTimeMillis();

        LOG.info("Playing the scenario");
        if (start > 0)
        {
            LOG.info("Skipping first {} events.", start);
            scenario.setPosition(start);
        }

        int maxPosition = finish > 0 ? Math.min(finish, scenario.getStepsCount()) : scenario.getStepsCount();
        LOG.info("Playing scenario. Start step: {}, finish step: {}. Steps count: {}", start, maxPosition, maxPosition
                - start);
        try
        {
            while (scenario.getPosition() != maxPosition)
            {
                LOG.info("Step " + scenario.getPosition());
                applyStep(scenario.getPosition());
                scenario.moveToNextStep();
            }
            LOG.info(String.format("Done(%d):playing", System.currentTimeMillis() - begin));
        }
        catch (Throwable throwable)
        {
            LOG.error("Playing interrupted by unhandled exception", throwable);
            throw throwable;
        }
        finally
        {
            if (scenario.getConfiguration().getCommonConfiguration().shouldCloseWebDriversOnError())
            {
                seleniumDriver.closeWebDrivers();
            }
        }
    }
}
