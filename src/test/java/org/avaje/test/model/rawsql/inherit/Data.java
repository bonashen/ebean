package org.avaje.test.model.rawsql.inherit;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rawinherit_data")
public class Data {

    @Id
    private Long id;

    private Integer val;

    @ManyToMany(mappedBy = "data")
    public List<Parent> parents = new ArrayList<>();

    public Data(int number) {
        this.val = number;
    }

    public Long getId() {
        return id;
    }

    public Integer getVal() {
        return val;
    }

}
