package com.avaje.tests.model.basic;

import com.avaje.ebean.annotation.Cache;
import com.avaje.ebean.annotation.CacheBeanTuning;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;


@Cache
@CacheBeanTuning(maxSecsToLive = 45)
@Entity
public class Article extends BasicDomain {

  String name;

  String author;

  @OneToMany(cascade = CascadeType.ALL)
  List<Section> sections;

  public Article() {
  }

  public Article(String name, String author) {
    this.name = name;
    this.author = author;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public List<Section> getSections() {
    return sections;
  }

  public void setSections(List<Section> sections) {
    this.sections = sections;
  }

  public void addSection(Section s) {
    if (sections == null) {
      sections = new ArrayList<>();
    }
    sections.add(s);
  }

}
