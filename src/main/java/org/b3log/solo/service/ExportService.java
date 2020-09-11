/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020, https://github.com/adlered
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.b3log.solo.service;

import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import jodd.io.ZipUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.ioc.BeanManager;
import org.b3log.latke.ioc.Inject;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.model.Plugin;
import org.b3log.latke.model.User;
import org.b3log.latke.repository.*;
import org.b3log.latke.repository.jdbc.JdbcRepository;
import org.b3log.latke.repository.jdbc.util.Connections;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.util.Execs;
import org.b3log.latke.util.Strings;
import org.b3log.solo.SoloServletListener;
import org.b3log.solo.bolo.Global;
import org.b3log.solo.model.*;
import org.b3log.solo.repository.*;
import org.b3log.solo.util.GitHubs;
import org.b3log.solo.util.Solos;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Export service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding (Solo Author)</a>
 * @author <a href="https://github.com/adlered">adlered (Bolo Author)</a>
 * @since 2.5.0
 */
@Service
public class ExportService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ExportService.class);

    /**
     * Archive date repository.
     */
    @Inject
    private ArchiveDateRepository archiveDateRepository;

    /**
     * Archive date-Article repository.
     */
    @Inject
    private ArchiveDateArticleRepository archiveDateArticleRepository;

    /**
     * Article repository.
     */
    @Inject
    private ArticleRepository articleRepository;

    /**
     * Category repository.
     */
    @Inject
    private CategoryRepository categoryRepository;

    /**
     * Category-Tag relation repository.
     */
    @Inject
    private CategoryTagRepository categoryTagRepository;

    /**
     * Comment repository.
     */
    @Inject
    private CommentRepository commentRepository;

    /**
     * Link repository.
     */
    @Inject
    private LinkRepository linkRepository;

    /**
     * Option repository.
     */
    @Inject
    private OptionRepository optionRepository;

    /**
     * Page repository.
     */
    @Inject
    private PageRepository pageRepository;

    /**
     * Plugin repository.
     */
    @Inject
    private PluginRepository pluginRepository;

    /**
     * Tag repository.
     */
    @Inject
    private TagRepository tagRepository;

    /**
     * Tag-Article repository.
     */
    @Inject
    private TagArticleRepository tagArticleRepository;

    /**
     * User repository.
     */
    @Inject
    private UserRepository userRepository;

    /**
     * Option query service.
     */
    @Inject
    private OptionQueryService optionQueryService;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Statistic query service.
     */
    @Inject
    private StatisticQueryService statisticQueryService;

    /**
     * Tag query service.
     */
    @Inject
    private TagQueryService tagQueryService;

    /**
     * Exports SQL in zip bytes.
     *
     * @return data bytes, returns {@code null} if occurs exception
     */
    public byte[] exportSQL() {
        final Latkes.RuntimeDatabase runtimeDatabase = Latkes.getRuntimeDatabase();
        if (Latkes.RuntimeDatabase.H2 != runtimeDatabase && Latkes.RuntimeDatabase.MYSQL != runtimeDatabase) {
            LOGGER.log(Level.ERROR, "Just support MySQL/H2 export now");

            return null;
        }

        final String dbUser = Latkes.getLocalProperty("jdbc.username");
        final String dbPwd = Latkes.getLocalProperty("jdbc.password");
        final String dbURL = Latkes.getLocalProperty("jdbc.URL");
        String sql = ""; // exported SQL script

        if (Latkes.RuntimeDatabase.MYSQL == runtimeDatabase) {
            String db = StringUtils.substringAfterLast(dbURL, "/");
            db = StringUtils.substringBefore(db, "?");

            try {
                if (StringUtils.isNotBlank(dbPwd)) {
                    sql = Execs.exec("mysqldump -u" + dbUser + " -p" + dbPwd + " --databases " + db, 60 * 1000 * 5);
                } else {
                    sql = Execs.exec("mysqldump -u" + dbUser + " --databases " + db, 60 * 1000 * 5);
                }
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Export failed, please check command \"mysqldump\" is on your system", e);

                return null;
            }
        } else if (Latkes.RuntimeDatabase.H2 == runtimeDatabase) {
            try (final Connection connection = Connections.getConnection();
                 final Statement statement = connection.createStatement()) {
                final StringBuilder sqlBuilder = new StringBuilder();
                final ResultSet resultSet = statement.executeQuery("SCRIPT");
                while (resultSet.next()) {
                    final String stmt = resultSet.getString(1);
                    sqlBuilder.append(stmt).append(Strings.LINE_SEPARATOR);
                }
                resultSet.close();

                sql = sqlBuilder.toString();
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Export failed", e);

                return null;
            }
        }

        if (StringUtils.isBlank(sql)) {
            LOGGER.log(Level.ERROR, "Export failed, executing export script returns empty");

            return null;
        }

        final String tmpDir = System.getProperty("java.io.tmpdir");
        final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");
        String localFilePath = tmpDir + File.separator + "solo-" + date + ".sql";
        final File localFile = new File(localFilePath);

        try {
            final byte[] data = sql.getBytes("UTF-8");
            try (final OutputStream output = new FileOutputStream(localFile)) {
                IOUtils.write(data, output);
            }

            final File zipFile = ZipUtil.zip(localFile);
            byte[] ret;
            try (final FileInputStream inputStream = new FileInputStream(zipFile)) {
                ret = IOUtils.toByteArray(inputStream);
            }

            FileUtils.deleteQuietly(localFile);
            FileUtils.deleteQuietly(zipFile);

            return ret;
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Export failed", e);

            return null;
        }
    }

    /**
     * Exports public articles to GitHub repo "solo-blog". 同步 GitHub solo-blog 仓库功能 https://github.com/88250/solo/issues/125
     */
    public void exportGitHub() {
        try {
            final JSONObject preference = optionQueryService.getPreference();
            if (null == preference) {
                return;
            }

            String pat = preference.optString(Option.ID_C_GITHUB_PAT);
            if (StringUtils.isBlank(pat)) {
                return;
            }

            LOGGER.log(Level.INFO, "Backup public articles to your GitHub repo [bolo-blog]....");

            final JSONObject mds = exportHexoMDs();
            JdbcRepository.dispose();
            final List<JSONObject> posts = (List<JSONObject>) mds.opt("posts");

            final String tmpDir = System.getProperty("java.io.tmpdir");
            final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");
            String localFilePath = tmpDir + File.separator + "solo-blog-repo-" + date;
            final File localFile = new File(localFilePath);

            final File postDir = new File(localFilePath + File.separator + "posts");
            exportHexoMd(posts, postDir.getPath());

            final File zipFile = ZipUtil.zip(localFile);
            byte[] zipData;
            try (final FileInputStream inputStream = new FileInputStream(zipFile)) {
                zipData = IOUtils.toByteArray(inputStream);
            }

            FileUtils.deleteQuietly(localFile);
            FileUtils.deleteQuietly(zipFile);

            final String clientTitle = preference.optString(Option.ID_C_BLOG_TITLE);
            final String clientSubtitle = preference.optString(Option.ID_C_BLOG_SUBTITLE);

            final JSONObject gitHubUser = GitHubs.getGitHubUser(pat);
            if (null == gitHubUser) {
                return;
            }

            final String loginName = gitHubUser.optString("login");
            final String repoName = "bolo-blog";

            boolean ok = GitHubs.createOrUpdateGitHubRepo(pat, loginName, repoName, "✍️ " + clientTitle + " - " + clientSubtitle, Latkes.getServePath());
            if (!ok) {
                return;
            }

            final String readme = genSoloBlogReadme(clientTitle, clientSubtitle, preference.optString(Option.ID_C_FAVICON_URL), loginName + "/" + repoName);
            JdbcRepository.dispose();
            ok = GitHubs.updateFile(pat, loginName, repoName, "README.md", readme.getBytes(StandardCharsets.UTF_8));
            if (ok) {
                ok = GitHubs.updateFile(pat, loginName, repoName, "backup.zip", zipData);
            }
            if (ok) {
                LOGGER.log(Level.INFO, "Exported public articles to your repo [bolo-blog]");
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Exports public articles to your repo failed: " + e.getMessage());
        }
    }

    /**
     * Exports public articles to admin's HacPai account.
     */
    public void exportHacPai(boolean manual) {
        LOGGER.log(Level.INFO, "Backup public articles to HacPai....");
        try {
            final JSONObject preference = optionQueryService.getPreference();
            if (null == preference) {
                return;
            }

            if (!manual) {
                if (!preference.optBoolean(Option.ID_C_SYNC_GITHUB)) {
                    return;
                }

                if (Latkes.RuntimeMode.PRODUCTION != Latkes.getRuntimeMode()) {
                    return;
                }
            }

            final JSONObject mds = exportHexoMDs();
            final List<JSONObject> posts = (List<JSONObject>) mds.opt("posts");

            final String tmpDir = System.getProperty("java.io.tmpdir");
            final String date = DateFormatUtils.format(new Date(), "yyyyMMddHHmmss");
            String localFilePath = tmpDir + File.separator + "solo-hexo-" + date;
            final File localFile = new File(localFilePath);

            final File postDir = new File(localFilePath + File.separator + "posts");
            exportHexoMd(posts, postDir.getPath());

            final File zipFile = ZipUtil.zip(localFile);
            byte[] zipData;
            try (final FileInputStream inputStream = new FileInputStream(zipFile)) {
                zipData = IOUtils.toByteArray(inputStream);
            }

            FileUtils.deleteQuietly(localFile);
            FileUtils.deleteQuietly(zipFile);

            final BeanManager beanManager = BeanManager.getInstance();
            UserQueryService userQueryService = beanManager.getReference(UserQueryService.class);
            String userName = userQueryService.getB3username();
            String userB3Key = userQueryService.getB3password();
            if (Option.DefaultPreference.DEFAULT_B3LOG_USERNAME.equals(userName)) {
                LOGGER.log(Level.INFO, "Backup public articles to HacPai skipped because using the default B3 account.");

                return;
            }

            final String clientTitle = preference.optString(Option.ID_C_BLOG_TITLE);
            final String clientSubtitle = preference.optString(Option.ID_C_BLOG_SUBTITLE);

            final Set<String> articleIds = new HashSet<>();
            final Filter published = new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED);

            final StringBuilder bodyBuilder = new StringBuilder("### 最新\n");
            final List<JSONObject> recentArticles = articleRepository.getList(new Query().setFilter(published).select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK).addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING).setPage(1, 20));
            for (final JSONObject article : recentArticles) {
                final String title = article.optString(Article.ARTICLE_TITLE);
                final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
                bodyBuilder.append("\n* [").append(title).append("](").append(link).append(")");
                articleIds.add(article.optString(Keys.OBJECT_ID));
            }
            bodyBuilder.append("\n\n");

            final StringBuilder mostViewBuilder = new StringBuilder();
            final List<JSONObject> mostViewArticles = articleRepository.getList(new Query().setFilter(published).select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK).addSort(Article.ARTICLE_VIEW_COUNT, SortDirection.DESCENDING).setPage(1, 40));
            int count = 0;
            for (final JSONObject article : mostViewArticles) {
                final String articleId = article.optString(Keys.OBJECT_ID);
                if (!articleIds.contains(articleId)) {
                    final String title = article.optString(Article.ARTICLE_TITLE);
                    final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
                    mostViewBuilder.append("\n* [").append(title).append("](").append(link).append(")");
                    articleIds.add(articleId);
                    count++;
                }
                if (20 <= count) {
                    break;
                }
            }
            if (0 < mostViewBuilder.length()) {
                bodyBuilder.append("### 热门\n").append(mostViewBuilder).append("\n\n");
            }

            final StringBuilder mostCmtBuilder = new StringBuilder();
            final List<JSONObject> mostCmtArticles = articleRepository.getList(new Query().setFilter(published).select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK).addSort(Article.ARTICLE_COMMENT_COUNT, SortDirection.DESCENDING).setPage(1, 60));
            count = 0;
            for (final JSONObject article : mostCmtArticles) {
                final String articleId = article.optString(Keys.OBJECT_ID);
                if (!articleIds.contains(articleId)) {
                    final String title = article.optString(Article.ARTICLE_TITLE);
                    final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
                    mostCmtBuilder.append("\n* [").append(title).append("](").append(link).append(")");
                    articleIds.add(articleId);
                    count++;
                }
                if (20 <= count) {
                    break;
                }
            }
            if (0 < mostCmtBuilder.length()) {
                bodyBuilder.append("### 热议\n").append(mostCmtBuilder);
            }

            final JSONObject stat = new JSONObject();
            stat.put("recentArticleTime", articleQueryService.getRecentArticleTime());
            final JSONObject statistic = statisticQueryService.getStatistic();
            stat.put("articleCount", statistic.getLong(Option.ID_T_STATISTIC_PUBLISHED_ARTICLE_COUNT));
            stat.put("commentCount", statistic.getLong(Option.ID_T_STATISTIC_PUBLISHED_BLOG_COMMENT_COUNT));
            stat.put("tagCount", tagQueryService.getTagCount());
            stat.put("skin", optionQueryService.getOptionById(Option.ID_C_SKIN_DIR_NAME).optString(Option.OPTION_VALUE));
            stat.put("mobileSkin", optionQueryService.getOptionById(Option.ID_C_MOBILE_SKIN_DIR_NAME).optString(Option.OPTION_VALUE));

            final HttpResponse response = HttpRequest.post("https://" + Global.HACPAI_DOMAIN + "/github/repos").
                    connectionTimeout(7000).timeout(60000).trustAllCerts(true).header("User-Agent", Solos.USER_AGENT).
                    form("userName", userName,
                            "userB3Key", userB3Key,
                            "clientName", "Solo",
                            "clientVersion", SoloServletListener.VERSION,
                            "clientHost", Latkes.getServePath(),
                            "clientFavicon", preference.optString(Option.ID_C_FAVICON_URL),
                            "clientTitle", clientTitle,
                            "clientSubtitle", clientSubtitle,
                            "clientBody", bodyBuilder.toString(),
                            "stat", stat.toString(),
                            "file", zipData).send();
            response.close();
            response.charset("UTF-8");
            LOGGER.info("Backup public articles to HacPai completed: " + response.bodyText());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Exports articles to github repo failed:" + e.getMessage());
        }
    }

    private String genSoloBlogReadme(final String blogTitle, final String blogSubTitle, final String favicon, final String repoFullName) throws RepositoryException {
        final Set<String> articleIds = new HashSet<>();
        final Filter published = new PropertyFilter(Article.ARTICLE_STATUS, FilterOperator.EQUAL, Article.ARTICLE_STATUS_C_PUBLISHED);
        final StringBuilder bodyBuilder = new StringBuilder("### 最新\n");
        final List<JSONObject> recentArticles = articleRepository.getList(new Query().setFilter(published).select(Keys.OBJECT_ID, Article.ARTICLE_TITLE, Article.ARTICLE_PERMALINK).addSort(Article.ARTICLE_CREATED, SortDirection.DESCENDING).setPage(1, 20));
        for (final JSONObject article : recentArticles) {
            final String title = article.optString(Article.ARTICLE_TITLE);
            final String link = Latkes.getServePath() + article.optString(Article.ARTICLE_PERMALINK);
            bodyBuilder.append("\n* [").append(title).append("](").append(link).append(")");
            articleIds.add(article.optString(Keys.OBJECT_ID));
        }
        bodyBuilder.append("\n\n");

        String ret = "<p align=\"center\"><img alt=\"${title}\" src=\"${favicon}\"></p><h2 align=\"center\">\n" +
                "${title}\n" +
                "</h2>\n" +
                "\n" +
                "<h4 align=\"center\">${subtitle}</h4>\n" +
                "<p align=\"center\">" +
                "<a title=\"${title}\" target=\"_blank\" href=\"https://github.com/${repoFullName}\"><img src=\"https://img.shields.io/github/last-commit/${repoFullName}.svg?style=flat-square&color=FF9900\"></a>\n" +
                "<a title=\"GitHub repo size in bytes\" target=\"_blank\" href=\"https://github.com/${repoFullName}\"><img src=\"https://img.shields.io/github/repo-size/${repoFullName}.svg?style=flat-square\"></a>\n" +
                "<a title=\"Bolo Version\" target=\"_blank\" href=\"https://github.com/adlered/bolo-solo\"><img src=\"https://img.shields.io/badge/bolo-${soloVer}-f1e05a.svg?style=flat-square&color=blueviolet\"></a>\n" +
                "<a title=\"Hits\" target=\"_blank\" href=\"https://github.com/88250/hits\"><img src=\"https://hits.b3log.org/${repoFullName}.svg\"></a>" +
                "</p>\n" +
                "\n" +
                "${body}\n\n" +
                "---\n" +
                "\n" +
                "本仓库通过 [Bolo](https://github.com/adlered/bolo-solo) 自动进行同步更新 ❤️ ";
        ret = ret.replace("${title}", blogTitle).
                replace("${subtitle}", blogSubTitle).
                replace("${favicon}", favicon).
                replace("${repoFullName}", repoFullName).
                replace("${soloVer}", SoloServletListener.BOLO_VERSION).
                replace("${body}", bodyBuilder.toString());
        return ret;
    }

    /**
     * Exports the specified articles to the specified dir path.
     *
     * @param articles the specified articles
     * @param dirPath  the specified dir path
     */
    public void exportHexoMd(final List<JSONObject> articles, final String dirPath) {
        articles.forEach(article -> {
            final String filename = Solos.sanitizeFilename(article.optString("title")) + ".md";
            final String text = article.optString("front") + "---" + Strings.LINE_SEPARATOR + article.optString("content");

            try {
                final String date = DateFormatUtils.format(article.optLong("created"), "yyyyMM");
                final String dir = dirPath + File.separator + date + File.separator;
                new File(dir).mkdirs();
                FileUtils.writeStringToFile(new File(dir + filename), text, "UTF-8");
            } catch (final Exception e) {
                LOGGER.log(Level.ERROR, "Write markdown file failed", e);
            }
        });
    }

    /**
     * Exports as Hexo markdown format.
     *
     * @return posts, password posts and drafts, <pre>
     * {
     *     "posts": [
     *         {
     *             "front": "", // yaml front matter,
     *             "title": "",
     *             "content": "",
     *             "created": long
     *         }, ....
     *     ],
     *     "passwords": [], // format is same as post
     *     "drafts": [] // format is same as post
     * }
     * </pre>
     */
    public JSONObject exportHexoMDs() {
        final JSONObject ret = new JSONObject();
        final List<JSONObject> posts = new ArrayList<>();
        ret.put("posts", (Object) posts);
        final List<JSONObject> passwords = new ArrayList<>();
        ret.put("passwords", (Object) passwords);
        final List<JSONObject> drafts = new ArrayList<>();
        ret.put("drafts", (Object) drafts);

        final JSONArray articles = getJSONs(articleRepository);
        for (int i = 0; i < articles.length(); i++) {
            final JSONObject article = articles.optJSONObject(i);
            final Map<String, Object> front = new LinkedHashMap<>();
            final String title = article.optString(Article.ARTICLE_TITLE);
            front.put("title", title);
            final String date = DateFormatUtils.format(article.optLong(Article.ARTICLE_CREATED), "yyyy-MM-dd HH:mm:ss");
            front.put("date", date);
            front.put("updated", DateFormatUtils.format(article.optLong(Article.ARTICLE_UPDATED), "yyyy-MM-dd HH:mm:ss"));
            final List<String> tags = Arrays.stream(article.optString(Article.ARTICLE_TAGS_REF).split(",")).filter(StringUtils::isNotBlank).map(String::trim).collect(Collectors.toList());
            if (tags.isEmpty()) {
                tags.add("Solo");
            }
            front.put("tags", tags);
            front.put("permalink", article.optString(Article.ARTICLE_PERMALINK));
            final JSONObject one = new JSONObject();
            one.put("front", new Yaml().dump(front));
            one.put("title", title);
            one.put("content", article.optString(Article.ARTICLE_CONTENT));
            one.put("created", article.optLong(Article.ARTICLE_CREATED));

            if (StringUtils.isNotBlank(article.optString(Article.ARTICLE_VIEW_PWD))) {
                passwords.add(one);
            } else if (Article.ARTICLE_STATUS_C_PUBLISHED == article.optInt(Article.ARTICLE_STATUS)) {
                posts.add(one);
            } else {
                drafts.add(one);
            }
        }

        return ret;
    }

    /**
     * Gets all data as JSON format.
     */
    public JSONObject getJSONs() {
        final JSONObject ret = new JSONObject();
        final JSONArray archiveDates = getJSONs(archiveDateRepository);
        ret.put(ArchiveDate.ARCHIVE_DATES, archiveDates);

        final JSONArray archiveDateArticles = getJSONs(archiveDateArticleRepository);
        ret.put(ArchiveDate.ARCHIVE_DATE + "_" + Article.ARTICLE, archiveDateArticles);

        final JSONArray articles = getJSONs(articleRepository);
        ret.put(Article.ARTICLES, articles);

        final JSONArray categories = getJSONs(categoryRepository);
        ret.put(Category.CATEGORIES, categories);

        final JSONArray categoryTags = getJSONs(categoryTagRepository);
        ret.put(Category.CATEGORY + "_" + Tag.TAG, categoryTags);

        final JSONArray comments = getJSONs(commentRepository);
        ret.put(Comment.COMMENTS, comments);

        final JSONArray links = getJSONs(linkRepository);
        ret.put(Link.LINKS, links);

        final JSONArray options = getJSONs(optionRepository);
        ret.put(Option.OPTIONS, options);

        final JSONArray pages = getJSONs(pageRepository);
        ret.put(Page.PAGES, pages);

        final JSONArray plugins = getJSONs(pluginRepository);
        ret.put(Plugin.PLUGINS, plugins);

        final JSONArray tags = getJSONs(tagRepository);
        ret.put(Tag.TAGS, tags);

        final JSONArray tagArticles = getJSONs(tagArticleRepository);
        ret.put(Tag.TAG + "_" + Article.ARTICLES, tagArticles);

        final JSONArray users = getJSONs(userRepository);
        ret.put(User.USERS, users);

        return ret;
    }

    private JSONArray getJSONs(final Repository repository) {
        try {
            return repository.get(new Query()).optJSONArray(Keys.RESULTS);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Gets data from repository [" + repository.getName() + "] failed", e);

            return new JSONArray();
        }
    }
}