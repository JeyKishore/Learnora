
package com.learnora.learnora.model;

public class LearningResource {

    private String title;
    private String description;
    private String url;
    private String thumbnail;
    private String platform;
    private String price;
    private String provider;

    public LearningResource() {
    }

    public LearningResource(
            String title,
            String description,
            String url,
            String thumbnail,
            String platform,
            String price,
            String provider) {

        this.title = title;
        this.description = description;
        this.url = url;
        this.thumbnail = thumbnail;
        this.platform = platform;
        this.price = price;
        this.provider = provider;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(String thumbnail) {
        this.thumbnail = thumbnail;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
