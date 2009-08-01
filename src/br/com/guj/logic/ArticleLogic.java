package br.com.guj.logic;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import net.jforum.entities.UserSession;
import net.jforum.util.preferences.ConfigKeys;

import org.hibernate.Criteria;
import org.hibernate.classic.Session;
import org.hibernate.criterion.Restrictions;
import org.vraptor.annotations.Component;
import org.vraptor.annotations.In;
import org.vraptor.annotations.InterceptedBy;
import org.vraptor.annotations.Out;
import org.vraptor.annotations.Parameter;
import org.vraptor.annotations.Viewless;
import org.vraptor.interceptor.MultipartRequestInterceptor;
import org.vraptor.interceptor.UploadedFileInformation;

import br.com.guj.hibernate.HibernateUtil;
import br.com.guj.model.Article;
import br.com.guj.model.Category;
import br.com.guj.model.Post;
import br.com.guj.model.Tag;
import br.com.guj.util.FileUtil;

@Component("article")
@InterceptedBy(MultipartRequestInterceptor.class)
public class ArticleLogic {
	private List<Post> postsBox;
	private List<Article> articlesBox;
	private List<Category> categories;
	private List<Article> articles;
	private List<Article> pendingArticles;
	private Tag tag;
	private boolean isLogged;
	private Article article;

	@Out
	private boolean isModerator;

	@Out
	private boolean isAuthor;

	@Out
	private List<String> linksOfCodes;

	@In
	private HttpServletRequest request;

	@In(required = false)
	private UploadedFileInformation images;

	@In(required = false)
	private UploadedFileInformation codes;

	public ArticleLogic(HttpSession session) {
		this.isLogged = "1".equals(session.getAttribute(ConfigKeys.LOGGED));
	}

	@SuppressWarnings("unchecked")
	protected List<Category> getAllCategories() {
		return HibernateUtil.getSession().createQuery(
			"from Category c ORDER BY c.name").setCacheable(true)
			.setCacheRegion("Categories").list();
	}

	private Article getArticle(long id) {
		return (Article) HibernateUtil.getSessionFactory().getCurrentSession()
			.get(Article.class, id);
	}

	@SuppressWarnings("unchecked")
	private List<Article> getPendingArticlesByAuthor(int userId) {

		Session session = HibernateUtil.getSessionFactory().getCurrentSession();

		Criteria criteria = session.createCriteria(Article.class);

		criteria.add(Restrictions.eq("userId", userId));
		criteria.add(Restrictions.eq("approved", false));
		criteria.add(Restrictions.isNull("category"));

		return criteria.list();
	}

	@Viewless
	public void addTag(@Parameter(key = "articleId") long articleId, @Parameter(key = "tags") String tags) {
		if (this.isLogged) {
			List<Tag> newTags = new ArrayList<Tag>();
			String[] p = tags.split(",");

			for (String tagName : p) {
				tagName = tagName.trim();
				Tag tag = this.findTagByName(tagName);

				if (tag == null) {
					tag = new Tag();
					tag.setName(tagName);
				}

				newTags.add(tag);
			}

			Article article = (Article) HibernateUtil.getSession().get(Article.class, articleId);
			newTags.removeAll(article.getTags());
			article.getTags().addAll(newTags);
		}
	}

	private Tag findTagByName(String tag) {
		return (Tag) HibernateUtil.getSession().createQuery(
			"from Tag t where lower(t.name) = lower(:name)").setParameter(
			"name", tag).uniqueResult();
	}

	@SuppressWarnings("unchecked")
	public void listByTag(@Parameter(key = "tag") String tagName) {
		Tag tag = this.findTagByName(tagName);

		if (tag == null) {
			this.tag = new Tag();
			this.tag.setName(tagName);
			this.articles = new ArrayList<Article>();
		}
		else {
			this.tag = tag;
			this.articles = HibernateUtil.getSession().createQuery(
				"select a from Article a join a.tags t where t = :tag")
				.setParameter("tag", tag).list();
		}
	}

	public void list() {
		this.categories = this.getAllCategories();
		this.setPendingArticles(new ArrayList<Article>());

		UserSession us = (UserSession) request.getAttribute("userSession");

		if (us != null) {
			this.getPendingArticles().addAll(this.getPendingArticlesByAuthor(us.getUserId()));
			this.isAuthor = true;
		}

		this.isModerator = us != null && us.isModerator();
	}

	public void show(@Parameter(key = "id") long id) {
		this.article = this.getArticle(id);

		UserSession us = (UserSession) this.request.getAttribute("userSession");

		this.isAuthor = us != null && us.getUserId() == this.article.getUserId();
		this.isModerator = us != null && us.isModerator();
	}

	public void write() {
	}

	@SuppressWarnings("deprecation")
	public void save(@Parameter(key = "content") String content, Article article) {
		if (!this.isLogged) {
			return;
		}

		String imagesPath = String.format("%s/files/", request.getContextPath());

		if (article.getId() == null) {
			UserSession us = (UserSession) request.getAttribute("userSession");

			article.setContent(content);
			article.setExclusive(true);
			article.setDate(new Date());
			article.setAuthor(us.getUsername());

			article.setUserId(us.getUserId());

			imagesPath += String.format("%d/%s/", article.getUserId(), article.getTitle().trim().toLowerCase());
			article.setContent(MessageFormat.format(article.getContent(), new Object[] { imagesPath }));

			HibernateUtil.getSessionFactory().getCurrentSession().save(article);

		}
		else {
			Article articleUpToDate = this.getArticle(article.getId());

			articleUpToDate.setTitle(article.getTitle());
			articleUpToDate.setSubtitle(article.getSubtitle());
			articleUpToDate.setAuthor(article.getAuthor());
			articleUpToDate.setAuthorEmail(article.getAuthorEmail());

			imagesPath += String.format("%d/%s/", articleUpToDate.getUserId(), article.getTitle().trim().toLowerCase());
			articleUpToDate.setContent(MessageFormat.format(content, new Object[] { imagesPath }));

			article = articleUpToDate;
		}

		String filesPath = request.getRealPath("/") + "files" + File.separator;

		String articlePath = article.getUserId().toString() + File.separator
				+ article.getTitle().trim().toLowerCase() + File.separator;

		if (this.images != null) {
			FileUtil.extractZipAndCopyFilesToDisk(this.images.getFile(), filesPath, articlePath);
		}

		if (this.codes != null) {
			FileUtil.prepareAndCopyCodes(this.codes.getFile(), this.codes.getFileName(), filesPath, articlePath);
		}
	}

	public void open(@Parameter(key = "id") long id) {
		this.article = this.getArticle(id);
		this.isAuthor = true;
		putCodesInLinks();
	}

	@SuppressWarnings("deprecation")
	private void putCodesInLinks() {

		String filesPath = request.getRealPath("/") + "files" + File.separator;

		String articlePath = this.article.getUserId().toString()
				+ File.separator + this.article.getTitle().trim().toLowerCase()
				+ File.separator;

		String fullPath = filesPath + articlePath;

		File directory = new File(fullPath);

		File[] listFiles = directory.listFiles();

		this.setLinksOfCodes(new ArrayList<String>());

		String linkPath = this.article.getUserId().toString() + "/"
				+ this.article.getTitle().trim().toLowerCase();

		if (listFiles != null) {

			for (File file : listFiles) {

				if (file.getName().lastIndexOf(".zip") != -1) {

					String link = "<a href=\"" + this.request.getContextPath()
							+ "/files/" + linkPath + "/"
							+ file.getName().trim() + "\">"
							+ file.getName().toLowerCase().trim() + "</a>";

					this.getLinksOfCodes().add(link);

				}

			}

		}
	}

	public List<Category> getCategories() {
		return this.categories;
	}

	public Article getArticle() {
		return article;
	}

	public List<Post> getPostsBox() {
		return postsBox;
	}

	public List<Article> getArticlesBox() {
		return articlesBox;
	}

	public List<Article> getArticles() {
		return articles;
	}

	public Tag getTag() {
		return tag;
	}

	public boolean isModerator() {
		return isModerator;
	}

	public void setModerator(boolean isModerator) {
		this.isModerator = isModerator;
	}

	public boolean isAuthor() {
		return isAuthor;
	}

	public void setAuthor(boolean isAuthor) {
		this.isAuthor = isAuthor;
	}

	public List<Article> getPendingArticles() {
		return pendingArticles;
	}

	public void setPendingArticles(List<Article> pendingArticles) {
		this.pendingArticles = pendingArticles;
	}

	public List<String> getLinksOfCodes() {
		return linksOfCodes;
	}

	public void setLinksOfCodes(List<String> linksOfCodes) {
		this.linksOfCodes = linksOfCodes;
	}
}
