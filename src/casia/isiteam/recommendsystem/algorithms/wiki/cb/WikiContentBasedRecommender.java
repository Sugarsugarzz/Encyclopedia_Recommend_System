package casia.isiteam.recommendsystem.algorithms.wiki.cb;

import casia.isiteam.recommendsystem.algorithms.RecommendAlgorithm;
import casia.isiteam.recommendsystem.model.Item;
import casia.isiteam.recommendsystem.utils.ConfigKit;
import casia.isiteam.recommendsystem.utils.DBKit;
import casia.isiteam.recommendsystem.utils.RecommendKit;
import casia.isiteam.recommendsystem.utils.TFIDF;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.ansj.app.keyword.Keyword;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * 基于内容的推荐算法实现
 *  思路：提取wiki词条的关键词列表（TF-IDF），以及每个用户的偏好关键词列表，计算关键词相似度计算，取最相似的 N 个wiki项推荐给用户。
 */
@SuppressWarnings("unchecked")
public class WikiContentBasedRecommender implements RecommendAlgorithm {

    private static final Logger logger = LogManager.getLogger(LogManager.ROOT_LOGGER_NAME);

    // TFIDF算法提取关键词的次数
    private static final int KEY_WORDS_NUM = ConfigKit.getInt("TFIDFKeywordsNum");
    // 利用基于内容推荐算法给每个用户推荐的wiki项条数
    private static final int recNum = ConfigKit.getInt("CBRecommendNum");

    /**
     * CB算法 推荐主函数
     * @param userIDs 用户ID列表
     */
    @Override
    public void recommend(List<Long> userIDs) {

        logger.info("基于内容的推荐 start at " + new Date());
        // 统计利用 CB算法 推荐的wiki项数量
        int count = 0;

        try {
            // 用户偏好衰减 + 根据用户浏览历史更新用户偏好
            new WikiUserPrefRefresher().refresher(userIDs);
            logger.info("用户偏好更新完成（衰减+浏览历史） at " + new Date());

            // （wiki项ID - 关键词列表）Map
            Map<Long, List<Keyword>> itemsKeywordsMap = new HashMap<>();
            // （wiki项ID - wiki项所属模块名）Map
            Map<Long, String> itemsModuleMap = new HashMap<>();
            // （用户ID - 用户wiki偏好）Map
            Map<Long, String> userWikiPrefListMap = RecommendKit.getUserWikiPreListMap(userIDs);
            // 时效内的所有wiki项
            List<Item> itemList = DBKit.getWikiItemsByPublishTime(RecommendKit.getInRecDate());

            // 将wiki项的关键词、TD-IDF值和所属模块名存入到对应Map
            for (Item item : itemList) {
                itemsKeywordsMap.put(item.getWiki_info_id(), TFIDF.getKeywordsByTFIDE(item.getName(), item.getSummary(), KEY_WORDS_NUM));
                itemsModuleMap.put(item.getWiki_info_id(), getWikiModuleName(item.getClassifyName()));
            }

            // 遍历用户，为用户推荐匹配的wiki项
            for (Long userID : userIDs) {
                // 获取该用户偏好
                Map<String, Object> map = JSONObject.parseObject(userWikiPrefListMap.get(userID));
                // 暂存与用户偏好相匹配的wiki项，（wiki项ID - 匹配值）Map
                Map<Long, Double> tempMatchMap = new LinkedHashMap<>();
                // 遍历时效内所有wiki项，获取wiki项与用户的匹配值
                for (Long itemID : itemsKeywordsMap.keySet()) {
                    // 获取用于在wiki项所属模块的用户偏好
                    String moduleName = itemsModuleMap.get(itemID);
                    Map<String, Object> moduleMap = (Map<String, Object>) map.get(moduleName);
                    // 如果用户在该模块下的偏好不为空，计算匹配值
                    if (!moduleMap.isEmpty()) {
                        tempMatchMap.put(itemID, getMatchValue(moduleMap, itemsKeywordsMap.get(itemID)));
                    }
                }

                // 去除匹配值为 0 的wiki项
                removeZeroItem(tempMatchMap);
                // 根据匹配值大小，从大到小排序
                tempMatchMap = sortMapByValue(tempMatchMap);
                // 初始化最终推荐wiki项列表
                Set<Long> toBeRecommended = tempMatchMap.keySet();
                logger.info("用户ID：" + userID);
                logger.info("本次基于内容推荐为用户生成：" + toBeRecommended.size() + " 条");
                // 过滤已推荐过的wiki项
                RecommendKit.filterRecommendedItems(toBeRecommended, userID, RecommendAlgorithm.WIKI);
                // 过滤用户已浏览的wiki项
                RecommendKit.filterBrowsedItems(toBeRecommended, userID, RecommendAlgorithm.WIKI);
                // 去除超出推荐数量的wiki项
                RecommendKit.removeOverSizeItems(toBeRecommended, recNum);
                // 将本次推荐结果存入表中
                RecommendKit.insertRecommendations(userID, toBeRecommended, RecommendAlgorithm.CB, RecommendAlgorithm.WIKI);
                logger.info("本次向用户 " + userID +" 成功推荐：" + toBeRecommended);

                count += toBeRecommended.size();

            }

        } catch (Exception e) {
            e.printStackTrace();
            logger.error("基于内容推荐算法 推荐失败！" + e);
        }

        logger.info("基于内容的推荐 has contributed " + (userIDs.size() == 0 ? 0 : count/userIDs.size()) + " recommendations on average");
        logger.info("基于内容的推荐 end at " + new Date());
    }

    /**
     * 计算用户偏好关键词列表与wiki项关键词列表的匹配值
     * @param map 用户偏好关键词
     * @param keywords wiki项关键词
     * @return 匹配值
     */
    private double getMatchValue(Map<String, Object> map, List<Keyword> keywords) {

        Set<String> keywordsSet = map.keySet();
        double matchValue = 0;
        for (Keyword keyword : keywords) {
            if (keywordsSet.contains(keyword.getName())) {
                matchValue += keyword.getScore() * Double.parseDouble(map.get(keyword.getName()).toString());
            }
        }

        return matchValue;
    }

    /**
     * 删除匹配值为 0 的wiki项项
     */
    private void removeZeroItem(Map<Long, Double> map) {

        map.keySet().removeIf(itemID -> map.get(itemID) == 0);
    }

    /**
     * 根据 Value 对 Map 排序
     */
    private Map<Long, Double> sortMapByValue(Map<Long, Double> map) {

        if (map.size() == 0)
            return map;

        List<Map.Entry<Long, Double>> list = new ArrayList<>(map.entrySet());
        list.sort((o1, o2) -> (int) (o2.getValue() - o1.getValue()));
        Map<Long, Double> resultMap = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : list) {
            resultMap.put(entry.getKey(), entry.getValue());
        }

        return resultMap;
    }

    public String getWikiModuleName(String moduleNameArray) {

        JSONArray jsonArray = JSONObject.parseArray(moduleNameArray);
        if (jsonArray.size() >= 1) {
            JSONObject obj = jsonArray.getJSONObject(0);
            for (String name : obj.keySet()) {
                return name;
            }
        }
        return "其他";
    }
}
