package org.jevintegration;

import de.hybris.platform.core.model.product.ProductModel;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;


/** The product text Jev reads: name and description in one language, without HTML. Less text keeps Jev on the question. */
public final class ProductText
{
	private static final int MAX_DESCRIPTION = 2000;

	private ProductText()
	{
	}

	public static Map<String, Object> state(final ProductModel product, final Locale locale)
	{
		final String description = stripHtml(Objects.toString(product.getDescription(locale), ""));
		return Map.of("product", Map.of(
				"name", Objects.toString(product.getName(locale), ""),
				"description", description.length() > MAX_DESCRIPTION ? description.substring(0, MAX_DESCRIPTION) : description));
	}

	public static String stripHtml(final String html)
	{
		// ponytail: a regex, not an HTML parser; enough to take markup out of product descriptions
		return html.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
	}
}
