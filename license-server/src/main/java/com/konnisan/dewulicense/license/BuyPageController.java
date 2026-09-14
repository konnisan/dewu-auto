package com.konnisan.dewulicense.license;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class BuyPageController {
    @GetMapping({"/buy", "/buy/"})
    public String buy(HttpServletRequest request) {
        String query = request.getQueryString();
        return "redirect:/buy/index.html" + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
