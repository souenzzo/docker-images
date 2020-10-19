(ns build-images
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [cljstache.core :as mustache]
    [clj-yaml.core :as yaml]))

(def debian-path "base-templates/debian.txt")
(def alpine-path "base-templates/alpine.txt")

(def tdeps-version "1.10.1.716")

(def all-images
  {:bases    {:zulu-openjdk-8  {:image         "azul/zulu-openjdk:8u252-8.46.0.19"
                                :template-path debian-path}
              :zulu-openjdk-11 {:image         "azul/zulu-openjdk:11.0.7-11.39.15"
                                :template-path debian-path}}
   :variants {:tools-deps {:template-path "variant-scripts/tools-deps.txt"}
              :intel-mkl  {:template-path "variant-scripts/intel-mkl.txt"}
              :dev-utils  {:template-path "variant-scripts/dev-utils.txt"}}
   :combos   [{:base :zulu-openjdk-8}

              {:base     :zulu-openjdk-8
               :variants [[:tools-deps {:version tdeps-version}]
                          [:dev-utils]]}

              {:base     :zulu-openjdk-8
               :name     ""
               :variants [[:intel-mkl {:version "2018.4-057"}]]}

              {:base     :zulu-openjdk-8
               :variants [[:tools-deps {:version tdeps-version}]
                          [:intel-mkl {:version "2018.4-057"}]
                          [:dev-utils]]}

              {:base :zulu-openjdk-11}

              {:base     :zulu-openjdk-11
               :variants [[:tools-deps {:version tdeps-version}]
                          [:dev-utils]]}

              {:base     :zulu-openjdk-11
               :variants [[:intel-mkl {:version "2018.4-057"}]]}

              {:base     :zulu-openjdk-11
               :variants [[:tools-deps {:version tdeps-version}]
                          [:intel-mkl {:version "2018.4-057"}]
                          [:dev-utils]]}]})

(defn render-file
  [path template-vars]
  (mustache/render (slurp path) template-vars))

(defn dockerfiles-content
  [images-spec]
  (map (fn [{:keys [base variants]}]
         (let [variant-str-combo (when-not (empty? variants)
                                   (str/join "-"
                                             (map (fn [[variant-name {:keys [version]}]]
                                                    (str (name variant-name)
                                                         (when version
                                                           (str "-" version))))
                                                  (sort-by first variants))))
               file-name (str (name base) (when variant-str-combo
                                            (str "-" variant-str-combo)))
               {base-template-path :template-path
                base-image         :image} (get-in images-spec [:bases base])]
           {:image-name (name base)
            :tag        (str (or variant-str-combo "base") "-" "$(echo $CIRCLE_SHA1 | cut -c -7)")
            :file-name  file-name
            :content    (render-file base-template-path
                                     {:from    base-image
                                      :content (str/join "\n\n"
                                                         (map (fn [[variant template-vars]]
                                                                (render-file
                                                                  (get-in images-spec [:variants variant :template-path])
                                                                  template-vars))
                                                              variants))})
            :file       (io/file "dockerfiles" (str file-name ".Dockerfile"))}))
       (:combos images-spec)))

(defn get-circleci-config-map
  [dockerfiles]
  (let [dockerfiles (map (fn [m]
                           (assoc m
                             :job-name
                             ;; circleci doesn't allow periods in the job name
                             (str/replace (:file-name m) "." "_"))) dockerfiles)]
    {:version   "2.1"

     :jobs      (reduce (fn [jobs-map {:keys [job-name image-name tag file]}]
                          (assoc jobs-map
                            job-name
                            {:docker [{:image "docker:17.05.0-ce-git"}]
                             :steps  [:checkout
                                      :setup_remote_docker
                                      {:run {:name    (str "Build & push " image-name)
                                             :command (str/join "\n"
                                                                [(format "echo %s" image-name)
                                                                 (format "docker build -t computesoftware/%s:%s . --file %s"
                                                                         image-name
                                                                         tag
                                                                         (.getPath file))
                                                                 "docker login -u $DOCKER_USER -p $DOCKER_PASS"
                                                                 (format "docker push computesoftware/%s:%s"
                                                                         image-name
                                                                         tag)])}}]}))
                        {} dockerfiles)

     :workflows {:version     "2"
                 :ci-workflow {:jobs (map (fn [{:keys [job-name]}]
                                            {job-name {:context "docker-env"}})
                                          dockerfiles)}}}))

(defn write-ci-and-dockerfiles
  [images-spec]
  (let [dockerfiles (dockerfiles-content images-spec)
        ci-config-map (get-circleci-config-map dockerfiles)]

    (doseq [{:keys [file content]} dockerfiles]
      (spit file content))

    (spit ".circleci/config.yml" (yaml/generate-string ci-config-map))))

(defn -main
  [& args]
  (write-ci-and-dockerfiles all-images))